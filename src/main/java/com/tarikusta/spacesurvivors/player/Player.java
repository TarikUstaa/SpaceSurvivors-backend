package com.tarikusta.spacesurvivors.player;

import java.util.UUID;

/**
 * A player as this application knows them — not as SQL stores them and not as HTTP
 * shows them.
 *
 * <p>Having its own type is what lets {@link PlayerService} return something without
 * either handing out a repository's row type or committing to a wire format. The
 * mapping to what a client sees lives in {@link PlayerDtos}.</p>
 */
public record Player(UUID id, String deviceId, String displayName, String country) {
}
