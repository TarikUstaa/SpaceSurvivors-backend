package com.tarikusta.spacesurvivors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point, and nothing else.
 *
 * <p>{@code @SpringBootApplication} scans this package and everything beneath it, which is
 * why every other class lives under {@code com.tarikusta.spacesurvivors} — one placed outside
 * that tree would compile, register no bean, and fail at runtime for no visible reason.</p>
 */
@SpringBootApplication
public class SpacesurvivorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpacesurvivorsApplication.class, args);
    }
}
