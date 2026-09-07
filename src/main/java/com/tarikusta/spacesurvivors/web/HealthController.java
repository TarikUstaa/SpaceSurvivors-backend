package com.tarikusta.spacesurvivors.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /health — the probe the game client and, later, the host will poll. */
@RestController
public class HealthController {

    private final HealthService health;

    public HealthController(HealthService health) {
        this.health = health;
    }

    @GetMapping("/health")
    public HealthService.Health health() {
        return health.check();
    }
}
