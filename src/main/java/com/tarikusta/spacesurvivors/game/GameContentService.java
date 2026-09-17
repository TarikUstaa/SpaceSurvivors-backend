package com.tarikusta.spacesurvivors.game;

import com.tarikusta.spacesurvivors.settings.AppSettings;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * What the game reads at its main menu without signing in: the announcement (and, next, the remote
 * game settings).
 *
 * <p>Read defensively. The document was validated when it was written, but this is the path every
 * player's menu takes, so a malformed row — typed into psql, or left by an older version — answers
 * "nothing to show" rather than an error on the main menu.</p>
 */
@Service
public class GameContentService {

    private final AppSettings settings;

    public GameContentService(AppSettings settings) {
        this.settings = settings;
    }

    public Optional<Announcement> announcement() {
        return settings.get(Announcement.SETTING_KEY).flatMap(GameContentService::toAnnouncement);
    }

    private static Optional<Announcement> toAnnouncement(JsonNode node) {
        String message = node.path("message").asString("").trim();
        Optional<String> level = Announcement.parseLevel(node.path("level").asString(""));
        if (message.isEmpty() || level.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Announcement(message, level.get()));
    }
}
