package com.tarikusta.spacesurvivors.player;

import com.tarikusta.spacesurvivors.auth.Caller;
import com.tarikusta.spacesurvivors.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlayerRepository players;

    public PlayerService(PlayerRepository players) {
        this.players = players;
    }

    /**
     * The player behind this caller, creating the profile the first time a device
     * appears. Runs on reads as well as writes: first contact is what makes someone a
     * player, and it is the only honest moment to stamp {@code first_login_date}.
     */
    @Transactional
    public UUID resolveOrCreate(Caller caller) {
        Optional<UUID> existing = players.findIdByDevice(caller.deviceId());
        if (existing.isPresent()) {
            players.touch(existing.get(), caller.ip());
            return existing.get();
        }
        return create(caller);
    }

    /** GET /v1/player — resolve the caller, creating the profile on first contact. */
    @Transactional
    public PlayerDtos.PlayerView view(Caller caller) {
        return toView(require(resolveOrCreate(caller)));
    }

    /** PATCH /v1/player — set the caller's display name and hand back the updated view. */
    @Transactional
    public PlayerDtos.PlayerView rename(Caller caller, String requestedName) {
        UUID playerId = resolveOrCreate(caller);
        applyName(playerId, requestedName);
        return toView(require(playerId));
    }

    private static PlayerDtos.PlayerView toView(PlayerRepository.PlayerRow row) {
        return new PlayerDtos.PlayerView(row.displayName(), row.country());
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
        try {
            if (!players.rename(playerId, name)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "player not found");
            }
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "name already taken");
        }
    }

    private PlayerRepository.PlayerRow require(UUID playerId) {
        return players.find(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "player not found"));
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
            Optional<UUID> created = players.insertIfFree(caller.deviceId(), generateName(), caller.ip());
            if (created.isPresent()) {
                return created.get();
            }
            Optional<UUID> raced = players.findIdByDevice(caller.deviceId());
            if (raced.isPresent()) {
                return raced.get();
            }
            // the name was taken, not the device — try another
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "could not allocate a player name");
    }

    /** e.g. {@code User104829}. Random rather than sequential so it does not leak the player count. */
    private static String generateName() {
        return "User" + (NAME_MIN_NUMBER + RANDOM.nextInt(NAME_NUMBER_RANGE));
    }

    private static void requireValidName(String name) {
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "name must be " + NAME_MIN + "-" + NAME_MAX + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!allowed) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "name may only contain letters, digits and underscore");
            }
        }
    }
}
