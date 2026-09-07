package com.tarikusta.spacesurvivors.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes for {@code /v1/auth/token}. */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * Body of {@code POST /v1/auth/token}.
     *
     * <p>Both values are generated once by the client and kept on the device. The minimum
     * length is enforced here because a short secret is guessable no matter how carefully
     * it is hashed, and the server has no other way to insist on one.</p>
     */
    public record TokenRequest(
            @NotBlank @Size(max = 64) String deviceId,
            @NotBlank @Size(min = 32, max = 200) String deviceSecret) {
    }

    /**
     * @param token     the access token, sent as {@code Authorization: Bearer <token>}
     * @param expiresIn seconds until it stops being accepted, so a client can refresh
     *                  before a request fails rather than after
     */
    public record TokenResponse(String token, long expiresIn) {
    }
}
