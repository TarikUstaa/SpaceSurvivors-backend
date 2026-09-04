package com.tarikusta.spacesurvivors.profile;

import com.tarikusta.spacesurvivors.user.UserService;
import com.tarikusta.spacesurvivors.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The rules of the cloud save — what "load" and "save" actually mean.
 * No SQL and no HTTP here: those are the repository's and the controller's jobs.
 */
@Service
public class ProfileService {

    /** Client profiles are a few hundred bytes; anything this big is a bug or abuse. */
    private static final int MAX_PROFILE_BYTES = 64 * 1024;

    private final ProfileRepository profiles;
    private final UserService users;
    private final ObjectMapper json;

    public ProfileService(ProfileRepository profiles, UserService users, ObjectMapper json) {
        this.profiles = profiles;
        this.users = users;
        this.json = json;
    }

    public Optional<LoadedProfile> load(String userId) {
        return profiles.find(userId)
                .map(stored -> new LoadedProfile(json.readTree(stored.json()), stored.version()));
    }

    /**
     * Save under optimistic locking:
     * <ul>
     *   <li>no row yet        &rarr; insert, version becomes 1</li>
     *   <li>clientVersion matches &rarr; update, version += 1</li>
     *   <li>clientVersion is stale &rarr; return the conflict (controller answers 409)</li>
     * </ul>
     * {@code @Transactional} = the touch + find + write run as one DB transaction.
     */
    @Transactional
    public SaveOutcome save(String userId, JsonNode profile, int clientVersion) {
        // the server decides the id, not whatever the client put in the body
        ObjectNode toStore = (ObjectNode) profile;
        toStore.put("userId", userId);
        String profileJson = json.writeValueAsString(toStore);

        if (profileJson.getBytes(StandardCharsets.UTF_8).length > MAX_PROFILE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "profile too large");
        }

        users.touch(userId);

        Optional<ProfileRepository.StoredProfile> existing = profiles.find(userId);
        if (existing.isEmpty()) {
            profiles.insert(userId, profileJson);
            return SaveOutcome.saved(1);
        }

        Optional<Integer> newVersion = profiles.update(userId, profileJson, clientVersion);
        if (newVersion.isPresent()) {
            return SaveOutcome.saved(newVersion.get());
        }

        // stale write — hand back what the server currently holds
        ProfileRepository.StoredProfile current = existing.get();
        return SaveOutcome.conflict(current.version(), json.readTree(current.json()));
    }

    public record LoadedProfile(JsonNode profile, int version) {
    }

    public record SaveOutcome(int version, JsonNode conflictProfile) {

        static SaveOutcome saved(int version) {
            return new SaveOutcome(version, null);
        }

        static SaveOutcome conflict(int serverVersion, JsonNode serverProfile) {
            return new SaveOutcome(serverVersion, serverProfile);
        }

        boolean isConflict() {
            return conflictProfile != null;
        }
    }
}
