package com.tarikusta.spacesurvivors.player;

import jakarta.validation.constraints.NotBlank;

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

    /** What the game shows on its profile screen. Neither id is exposed. */
    public record PlayerView(String displayName, String country) {
    }
}
