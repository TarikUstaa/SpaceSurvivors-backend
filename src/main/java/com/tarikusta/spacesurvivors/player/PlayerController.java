package com.tarikusta.spacesurvivors.player;

import com.tarikusta.spacesurvivors.auth.CurrentPlayer;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** HTTP for the player's own profile. Every method delegates; no rules live here. */
@RestController
@RequestMapping("/v1/player")
public class PlayerController {

    private final PlayerService players;

    public PlayerController(PlayerService players) {
        this.players = players;
    }

    /** GET /v1/player — who am I? Creates the profile on first contact. */
    @GetMapping
    public PlayerDtos.PlayerView me(@CurrentPlayer UUID playerId) {
        return PlayerDtos.PlayerView.of(players.view(playerId));
    }

    /**
     * PATCH /v1/player — choose a display name.
     * 400 if it breaks the name rules, 409 if someone already has it.
     */
    @PatchMapping
    public PlayerDtos.PlayerView rename(@CurrentPlayer UUID playerId,
                                        @Valid @RequestBody PlayerDtos.RenameRequest body) {
        return PlayerDtos.PlayerView.of(players.rename(playerId, body.displayName()));
    }
}
