package com.tarikusta.spacesurvivors.player;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for {@code /v1/player}. */
public final class PlayerDtos {

    private PlayerDtos() {
    }

    /**
     * Body of {@code PATCH /v1/player}.
     *
     * <p>Only presence is checked here. Length and character rules live in
     * {@link PlayerService} because the generated default name has to satisfy them too,
     * and a rule that applies to more than one caller is not a property of this payload.</p>
     */
    public record RenameRequest(@NotBlank String displayName) {
    }

    /**
     * What the game shows on its profile screen.
     *
     * <p><b>The player's own id is here; the device id is not.</b> They are different
     * kinds of thing. The caller has already proved they are this player, so their own
     * account number is theirs to see — it is what they would quote when asking for help,
     * and a profile screen that cannot name the account is a poor one. The device id stays
     * hidden because it is half of a credential; {@code last_ip} stays hidden because it
     * is ours rather than theirs.</p>
     *
     * <p>None of this loosens anything about <em>other</em> players: the leaderboard still
     * carries display names and no ids at all.</p>
     *
     * @param firstLoginDate when this player was first seen, as an ISO-8601 instant. The
     *                       client decides how to render a date — the server has no
     *                       business guessing a locale or a timezone.
     */
    public record PlayerView(UUID playerId, String displayName, String country,
                             Instant firstLoginDate) {

        public static PlayerView of(Player player) {
            return new PlayerView(player.id(), player.displayName(), player.country(),
                    player.firstLoginDate());
        }
    }
}
