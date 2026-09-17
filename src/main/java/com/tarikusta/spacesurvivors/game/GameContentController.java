package com.tarikusta.spacesurvivors.game;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Public, read-only content for the game's menus.
 *
 * <p><b>No token</b>, deliberately: the announcement is most needed exactly when signing in is
 * broken ("maintenance tonight", "sign-in is down, we're on it"), and it says nothing about any
 * player. Listed in {@code SecurityConfig.PUBLIC}.</p>
 *
 * <p><b>Cached for a minute</b> by whatever sits between the game and the service. An announcement
 * changes a few times a month and is read on every visit to the main menu; a minute's delay before
 * a new one shows is not something anyone will notice.</p>
 */
@RestController
@RequestMapping("/v1")
public class GameContentController {

    static final CacheControl ONE_MINUTE = CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic();

    private final GameContentService content;

    public GameContentController(GameContentService content) {
        this.content = content;
    }

    /** 200 with the announcement, or 204 when there is none — "nothing to show" is not an error. */
    @GetMapping("/announcement")
    public ResponseEntity<Announcement> announcement() {
        return content.announcement()
                .map(a -> ResponseEntity.ok().cacheControl(ONE_MINUTE).body(a))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(ONE_MINUTE).build());
    }
}
