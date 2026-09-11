package com.tarikusta.spacesurvivors.progress;

import com.tarikusta.spacesurvivors.exception.InvalidInputException;
import com.tarikusta.spacesurvivors.exception.NotFoundException;
import com.tarikusta.spacesurvivors.exception.TooLargeException;
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

    private final PlayerProgressRepository progress;
    private final ObjectMapper json;

    /**
     * No PlayerService: the token already carried the player id, so this service never has
     * to ask who is calling.
     */
    public ProgressService(PlayerProgressRepository progress, ObjectMapper json) {
        this.progress = progress;
        this.json = json;
    }

    /**
     * GET /v1/progress. A player with no save is a 404, not an empty object: the client
     * has to tell "nothing stored yet, upload mine" apart from "stored, and it is empty".
     */
    @Transactional(readOnly = true)
    public ProgressDtos.ProgressView load(UUID playerId) {
        return progress.findById(playerId)
                .map(row -> new ProgressDtos.ProgressView(
                        json.readTree(row.getProgressData()), row.getVersion()))
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
    public SaveOutcome save(UUID playerId, ProgressDtos.SaveRequest request) {
        ObjectNode toStore = requireObject(request.progress());

        // the server owns the identity, whatever the client put in the blob
        toStore.put("userId", playerId.toString());

        String progressJson = json.writeValueAsString(toStore);
        if (progressJson.getBytes(StandardCharsets.UTF_8).length > MAX_PROGRESS_BYTES) {
            throw new TooLargeException("progress exceeds 64 KB");
        }

        Optional<PlayerProgress> existing = progress.findById(playerId);
        if (existing.isEmpty()) {
            PlayerProgress created = progress.save(new PlayerProgress(playerId, progressJson));
            return new SaveOutcome.Accepted(created.getVersion());
        }

        // Compare before writing rather than letting @Version raise. A raised
        // OptimisticLockException marks the transaction rollback-only, so the query that
        // fetches the server's copy for the 409 body could not run afterwards — the same
        // trap that PlayerService.create fell into with DuplicateKeyException.
        PlayerProgress row = existing.get();
        if (row.getVersion() != request.version()) {
            return new SaveOutcome.Conflict(row.getVersion(), json.readTree(row.getProgressData()));
        }

        // The entity is managed, so this alone would be written at commit. Flushing now
        // makes @Version's guard fire here, where it still guards something: a writer
        // that committed between our read and this write.
        row.setProgressData(progressJson);
        PlayerProgress saved = progress.saveAndFlush(row);
        return new SaveOutcome.Accepted(saved.getVersion());
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
