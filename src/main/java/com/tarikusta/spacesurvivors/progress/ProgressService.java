package com.tarikusta.spacesurvivors.progress;

import com.tarikusta.spacesurvivors.auth.Caller;
import com.tarikusta.spacesurvivors.player.PlayerService;
import com.tarikusta.spacesurvivors.domain.InvalidInputException;
import com.tarikusta.spacesurvivors.domain.NotFoundException;
import com.tarikusta.spacesurvivors.domain.TooLargeException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * The rules of the cloud save — what "load" and "save" actually mean.
 * No SQL and no HTTP status juggling: those belong to the repository and the controller.
 */
@Service
public class ProgressService {

    /** A real save is a few hundred bytes; anything this large is a bug or abuse. */
    private static final int MAX_PROGRESS_BYTES = 64 * 1024;

    private final ProgressRepository progress;
    private final PlayerService players;
    private final ObjectMapper json;

    public ProgressService(ProgressRepository progress, PlayerService players, ObjectMapper json) {
        this.progress = progress;
        this.players = players;
        this.json = json;
    }

    /**
     * GET /v1/progress. A player with no save is a 404, not an empty object: the client
     * has to tell "nothing stored yet, upload mine" apart from "stored, and it is empty".
     */
    @Transactional
    public ProgressDtos.ProgressView load(Caller caller) {
        UUID playerId = players.resolveOrCreate(caller);
        return progress.find(playerId)
                .map(stored -> new ProgressDtos.ProgressView(json.readTree(stored.json()), stored.version()))
                .orElseThrow(() -> new NotFoundException("no progress stored yet"));
    }

    /**
     * PUT /v1/progress, under optimistic locking:
     * <ul>
     *   <li>no row yet             &rarr; insert, version becomes 1</li>
     *   <li>version matches        &rarr; update, version + 1</li>
     *   <li>version is stale       &rarr; {@link SaveOutcome.Conflict}</li>
     * </ul>
     */
    @Transactional
    public SaveOutcome save(Caller caller, ProgressDtos.SaveRequest request) {
        ObjectNode toStore = requireObject(request.progress());
        UUID playerId = players.resolveOrCreate(caller);

        // the server owns the identity, whatever the client put in the blob
        toStore.put("userId", playerId.toString());

        String progressJson = json.writeValueAsString(toStore);
        if (progressJson.getBytes(StandardCharsets.UTF_8).length > MAX_PROGRESS_BYTES) {
            throw new TooLargeException("progress exceeds 64 KB");
        }

        // Try the write first. The common case — an established player saving again —
        // then costs one statement, and only the two rarer outcomes need a look at
        // what is actually stored.
        Optional<Integer> newVersion = progress.update(playerId, progressJson, request.version());
        if (newVersion.isPresent()) {
            return new SaveOutcome.Accepted(newVersion.get());
        }

        // Nothing was updated, which means either there is no row yet or the version
        // had moved on. Only now is it worth asking which.
        Optional<ProgressRepository.StoredProgress> existing = progress.find(playerId);
        if (existing.isEmpty()) {
            progress.insert(playerId, progressJson);
            return new SaveOutcome.Accepted(1);
        }

        ProgressRepository.StoredProgress current = existing.get();
        return new SaveOutcome.Conflict(current.version(), json.readTree(current.json()));
    }

    /**
     * A copy of the body, once it is known to be a JSON object.
     *
     * <p>Returning the narrowed type means the caller cannot forget the check, and
     * copying means the request Jackson handed us is never mutated on its way through.</p>
     */
    private static ObjectNode requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new InvalidInputException("progress must be a JSON object");
        }
        return (ObjectNode) body.deepCopy();
    }

    /**
     * What a save attempt produced. A sealed type so the controller can turn it into a
     * status code with an exhaustive switch — the compiler rejects a forgotten case,
     * and the decision itself stays here rather than in the controller.
     */
    public sealed interface SaveOutcome {

        record Accepted(int version) implements SaveOutcome {
        }

        record Conflict(int serverVersion, JsonNode serverProgress) implements SaveOutcome {
        }
    }
}
