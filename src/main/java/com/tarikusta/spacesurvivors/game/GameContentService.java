package com.tarikusta.spacesurvivors.game;

import com.tarikusta.spacesurvivors.settings.AppSettings;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the game reads at its main menu without signing in: the announcement and the remote game
 * settings.
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

    /**
     * The game settings an operator has overridden, keyed as the game reads them. Only overrides:
     * a tunable left at its default is absent, and the game uses its own value.
     *
     * <p>Checked again on the way out, against today's bounds. A key since removed from
     * {@link GameTunable}, or a value outside a range that has since been narrowed, is dropped
     * rather than sent to every player.</p>
     */
    public Map<String, BigDecimal> gameConfig() {
        Map<String, BigDecimal> overrides = new LinkedHashMap<>();
        settings.get(GameTunable.SETTING_KEY).ifPresent(node -> node.forEachEntry((key, value) ->
                GameTunable.byKey(key)
                        .filter(t -> value.isNumber())
                        .flatMap(t -> t.accept(value.decimalValue()))
                        .ifPresent(accepted -> overrides.put(key, accepted))));
        return overrides;
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
