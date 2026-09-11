package com.tarikusta.spacesurvivors.player;

import com.tarikusta.spacesurvivors.exception.AlreadyTakenException;
import com.tarikusta.spacesurvivors.exception.AuthenticationFailedException;
import com.tarikusta.spacesurvivors.exception.InvalidInputException;
import com.tarikusta.spacesurvivors.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

/**
 * Identity rules: turning "a device is calling" into "this is player X".
 *
 * <p>Every other service starts here, because {@code player_id} is what the rest of
 * the schema is keyed on. Nothing downstream knows a device id exists.</p>
 */
@Service
public class PlayerService {

    /** Always exactly six digits: User100000..User999999, so 900k names to draw from. */
    private static final int NAME_MIN_NUMBER = 100_000;
    private static final int NAME_NUMBER_RANGE = 900_000;

    /** Enough attempts that exhausting them means something is genuinely wrong. */
    private static final int MAX_NAME_ATTEMPTS = 5;

    private static final int NAME_MIN = 3;
    private static final int NAME_MAX = 16;

    private static final Logger log = LoggerFactory.getLogger(PlayerService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlayerProfileRepository players;
    private final PasswordEncoder passwordEncoder;

    public PlayerService(PlayerProfileRepository players, PasswordEncoder passwordEncoder) {
        this.players = players;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Establish who a device is, registering it the first time it appears.
     *
     * <p>The only place a device id is turned into a player. Every other request arrives
     * with a signed token that already carries the player id, so nothing else has to look
     * a device up — that lookup used to run on every single call.</p>
     *
     * <p>An unknown device is registered rather than refused: the game has no sign-up
     * screen, and first contact is what makes someone a player. A device that predates the
     * secret adopts the one it presents, because refusing it would lock an existing player
     * out of progress they already earned.</p>
     *
     * <p>Every failure gives the same answer. Saying whether a device is registered, or
     * whether it was the secret that was wrong, would let someone enumerate accounts one
     * request at a time.</p>
     */
    @Transactional
    public UUID authenticateDevice(String deviceId, String rawSecret, String ip) {
        Optional<PlayerProfile> existing = players.findByDeviceId(deviceId);
        if (existing.isEmpty()) {
            return register(deviceId, rawSecret, ip);
        }

        PlayerProfile profile = existing.get();
        String storedHash = profile.getDeviceSecretHash();
        if (storedHash == null) {
            profile.setDeviceSecretHash(passwordEncoder.encode(rawSecret));
            players.saveAndFlush(profile);
        } else if (!passwordEncoder.matches(rawSecret, storedHash)) {
            throw new AuthenticationFailedException("device id or secret is not recognised");
        }

        // Authenticating is the natural "last seen": it happens once a session rather than
        // on every read, so nothing has to write on the hot path any more.
        players.touch(profile.getPlayerId(), ip);
        return profile.getPlayerId();
    }

    /** GET /v1/player — the authenticated player. */
    @Transactional(readOnly = true)
    public Player view(UUID playerId) {
        return require(playerId);
    }

    /** PATCH /v1/player — set the display name and hand back the updated player. */
    @Transactional
    public Player rename(UUID playerId, String requestedName) {
        applyName(playerId, requestedName);
        return require(playerId);
    }

    /**
     * Write a chosen name, once it passes the rules.
     *
     * <p>Validation lives here rather than on the request record because it is a rule
     * about names, not about one endpoint's payload — the generated default has to
     * satisfy it too.</p>
     *
     * <p>No "is this name free?" query first: between the check and the write another
     * request could take it. The unique index is the real guard, and this is the one
     * place a raised violation is safe — nothing else runs afterwards.</p>
     */
    private void applyName(UUID playerId, String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        requireValidName(name);

        PlayerProfile profile = players.findById(playerId)
                .orElseThrow(() -> new NotFoundException("player not found"));
        profile.setDisplayName(name);
        try {
            // saveAndFlush, not save: dirty checking would otherwise write at commit, and
            // the unique violation would surface outside this try block.
            players.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            throw new AlreadyTakenException("name already taken");
        }
        // Names are public and unique, so a rename is the one profile change anybody may
        // later need to trace — a report about an offensive name starts here.
        log.info("player {} renamed", playerId);
    }

    private Player require(UUID playerId) {
        return players.findById(playerId)
                .map(PlayerProfile::toPlayer)
                .orElseThrow(() -> new NotFoundException("player not found"));
    }

    /**
     * First sighting of a device: mint a profile with a generated name and the secret it
     * presented.
     *
     * <p>An insert that changes nothing means one of two things, and they need opposite
     * responses. If a row for this device now exists, a concurrent request for the same
     * device got there first and its player is the right answer — retrying would be wrong.
     * Otherwise the generated name was taken, and a different one will work.</p>
     *
     * <p>This deliberately does not go through a caught {@code DuplicateKeyException}: a
     * raised constraint violation aborts the transaction this method runs in, so the
     * recovery query and the next attempt would both fail. See
     * {@link PlayerProfileRepository#insertIfFree}.</p>
     */
    private UUID register(String deviceId, String rawSecret, String ip) {
        String secretHash = passwordEncoder.encode(rawSecret);
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            players.insertIfFree(deviceId, generateName(), ip, secretHash);

            // A row for this device now means either our insert landed or a concurrent
            // request for the same device won — both are the right answer. No row means
            // the generated name was taken, so try a different one.
            Optional<PlayerProfile> profile = players.findByDeviceId(deviceId);
            if (profile.isPresent()) {
                // Once per player for the lifetime of the account, so it is worth INFO:
                // it is the only record of when and how the population grew.
                log.info("player created for a new device, attempt {}", attempt + 1);
                return profile.get().getPlayerId();
            }
        }
        throw new IllegalStateException("could not allocate a player name after "
                + MAX_NAME_ATTEMPTS + " attempts");
    }

    /**
     * e.g. {@code User104829}. Random rather than sequential, so the name does not leak how
     * many players there are.
     */
    private static String generateName() {
        return "User" + (NAME_MIN_NUMBER + RANDOM.nextInt(NAME_NUMBER_RANGE));
    }

    private static void requireValidName(String name) {
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw new InvalidInputException(
                    "name must be " + NAME_MIN + "-" + NAME_MAX + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!allowed) {
                throw new InvalidInputException(
                        "name may only contain letters, digits and underscore");
            }
        }
    }
}
