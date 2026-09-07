package com.tarikusta.spacesurvivors.player;

import com.tarikusta.spacesurvivors.auth.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tarikusta.spacesurvivors.domain.AlreadyTakenException;
import com.tarikusta.spacesurvivors.domain.InvalidInputException;
import com.tarikusta.spacesurvivors.domain.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
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

    public PlayerService(PlayerProfileRepository players) {
        this.players = players;
    }

    /**
     * The player behind this caller, if there already is one.
     *
     * <p>Read-only, and used by every endpoint that only reads. That distinction matters:
     * {@code GET} is defined as safe, and a read path that quietly created rows would
     * break that promise, put a write on the hot path of the busiest requests, and let
     * anyone fill the table by sending fresh device ids at it.</p>
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resolve(Caller caller) {
        return players.findByDeviceId(caller.deviceId()).map(PlayerProfile::getPlayerId);
    }

    /**
     * The player behind this caller, creating the profile if this device is new.
     *
     * <p>Only for endpoints that were going to write anyway — plus {@code GET /v1/player},
     * which is the endpoint whose entire job is "who am I", and so is the one honest place
     * to stamp {@code first_login_date}.</p>
     */
    @Transactional
    public UUID resolveOrCreate(Caller caller) {
        Optional<PlayerProfile> existing = players.findByDeviceId(caller.deviceId());
        if (existing.isPresent()) {
            UUID playerId = existing.get().getPlayerId();
            players.touch(playerId, caller.ip());
            return playerId;
        }
        return create(caller);
    }

    /** GET /v1/player — resolve the caller, creating the profile on first contact. */
    @Transactional
    public Player view(Caller caller) {
        return require(resolveOrCreate(caller));
    }

    /** PATCH /v1/player — set the caller's display name and hand back the updated player. */
    @Transactional
    public Player rename(Caller caller, String requestedName) {
        UUID playerId = resolveOrCreate(caller);
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
     * First sighting of a device: mint a profile with a generated name.
     *
     * <p>An insert that changes nothing means one of two things, and they need opposite
     * responses. If a row for this device now exists, a concurrent request for the same
     * device got there first and its player is the right answer — retrying would be
     * wrong. Otherwise the generated name was taken, and a different one will work.</p>
     *
     * <p>This deliberately does not go through a caught {@code DuplicateKeyException}:
     * a raised constraint violation aborts the transaction this method runs in, so the
     * recovery query and the next attempt would both fail. See
     * {@link PlayerRepository#insertIfFree}.</p>
     */
    private UUID create(Caller caller) {
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            players.insertIfFree(caller.deviceId(), generateName(), caller.ip());

            // A row for this device now means either our insert landed or a concurrent
            // request for the same device won — both are the right answer. No row means
            // the generated name was taken, so try a different one.
            Optional<PlayerProfile> profile = players.findByDeviceId(caller.deviceId());
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

    /** e.g. {@code User104829}. Random rather than sequential so it does not leak the player count. */
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
