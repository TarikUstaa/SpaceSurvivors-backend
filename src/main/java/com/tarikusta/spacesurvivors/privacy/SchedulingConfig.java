package com.tarikusta.spacesurvivors.privacy;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled}. Its own class, next to the one job that needs it, rather than on the
 * application class — so the reason it is switched on is one click away from the switch.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
