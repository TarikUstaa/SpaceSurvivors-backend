package com.tarikusta.spacesurvivors.game;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Every game value the backoffice may change remotely, with the bounds that make changing it safe.
 *
 * <p><b>A closed list, not a free-form key/value editor.</b> A typo in a free-form key does
 * nothing and says nothing; a value with no bounds turns "enemy health ×10" into "×1000" with one
 * extra zero, for every player at once. Each entry here names the one place in the game that reads
 * it (see the Unity client's {@code RemoteConfig}), and a key the game does not read has no reason
 * to exist.</p>
 *
 * <p>{@code gameDefault} is what the game uses when there is no override. It is copied from the
 * game's own assets for display only — the server never sends it; the game falls back to its own
 * value, so the two cannot disagree about what "default" means in play.</p>
 */
public enum GameTunable {

    CURSE_CHANCE("curseChance", "Curse card chance",
            "Chance that a level-up offers a curse card.", "0", "1", false, "0.25"),
    CURSE_MIN_LEVEL("curseMinLevel", "Curse minimum level",
            "The first level at which a curse card can be offered.", "1", "100", true, "7"),
    ELITE_CHANCE("eliteChance", "Elite enemy chance",
            "Chance that a spawned enemy is an elite.", "0", "1", false, "0.04"),
    ENEMY_HEALTH_SCALE("enemyHealthScale", "Enemy health ×",
            "Multiplies the difficulty curve's enemy health at every moment of a run.", "0.25", "5", false, "1"),
    SPAWN_RATE_SCALE("spawnRateScale", "Spawn rate ×",
            "Multiplies how many enemies spawn per second.", "0.25", "5", false, "1"),
    XP_GAIN_SCALE("xpGainScale", "XP gain ×",
            "Multiplies the experience every pickup gives.", "0.25", "5", false, "1"),
    RELIC_DROP_CHANCE("relicDropChance", "Relic drop chance",
            "Chance that an ordinary enemy drops a relic (bosses always do).", "0", "0.2", false, "0.005");

    public static final String SETTING_KEY = "game_config";

    private final String key;
    private final String label;
    private final String description;
    private final BigDecimal min;
    private final BigDecimal max;
    private final boolean whole;
    private final String gameDefault;

    GameTunable(String key, String label, String description, String min, String max,
                boolean whole, String gameDefault) {
        this.key = key;
        this.label = label;
        this.description = description;
        this.min = new BigDecimal(min);
        this.max = new BigDecimal(max);
        this.whole = whole;
        this.gameDefault = gameDefault;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String min() {
        return min.toPlainString();
    }

    public String max() {
        return max.toPlainString();
    }

    public boolean whole() {
        return whole;
    }

    public String gameDefault() {
        return gameDefault;
    }

    /** The value if it is a number this tunable accepts, else empty. */
    public Optional<BigDecimal> accept(BigDecimal value) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            return Optional.empty();
        }
        if (whole && value.stripTrailingZeros().scale() > 0) {
            return Optional.empty();
        }
        return Optional.of(value.stripTrailingZeros());
    }

    public static Optional<GameTunable> byKey(String key) {
        for (GameTunable tunable : values()) {
            if (tunable.key.equals(key)) {
                return Optional.of(tunable);
            }
        }
        return Optional.empty();
    }
}
