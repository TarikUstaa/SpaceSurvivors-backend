package com.tarikusta.spacesurvivors.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/auth/token} — the one endpoint that takes a device credential.
 *
 * <p>Public, necessarily: requiring a token to obtain a token is a circle. It is also the
 * only place the device secret is ever sent, which is the point of the exchange — every
 * other request carries a token that expires instead.</p>
 */
@RestController
@RequestMapping("/v1/auth")
public class TokenController {

    private final TokenService tokens;

    public TokenController(TokenService tokens) {
        this.tokens = tokens;
    }

    /**
     * 200 with a token, or 401 when the credential is not recognised. An unknown device is
     * registered rather than refused — the game has no sign-up screen.
     *
     * <p>{@link HttpServletRequest} appears here and nowhere else in the application: the
     * caller's address is recorded at authentication, so this is the only point that needs
     * it, and the service layer stays free of servlet types.</p>
     */
    @PostMapping("/token")
    public AuthDtos.TokenResponse token(@Valid @RequestBody AuthDtos.TokenRequest body,
                                        HttpServletRequest request) {
        return tokens.issue(body, ClientAddress.of(request));
    }
}
