package com.tarikusta.spacesurvivors.auth;

import com.tarikusta.spacesurvivors.player.PlayerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Turns a device credential into a short-lived access token.
 *
 * <p>The split is deliberate: {@link PlayerService} decides <em>who</em> a device is,
 * because that is a question about players; this class only knows how to state the answer
 * in a form the rest of the system can verify without asking again.</p>
 */
@Service
public class TokenService {

    private final PlayerService players;
    private final JwtEncoder encoder;
    private final Duration ttl;

    public TokenService(PlayerService players, JwtEncoder encoder,
                        @Value("${app.jwt.ttl}") Duration ttl) {
        this.players = players;
        this.encoder = encoder;
        this.ttl = ttl;
    }

    public AuthDtos.TokenResponse issue(AuthDtos.TokenRequest request, String ip) {
        UUID playerId = players.authenticateDevice(request.deviceId(), request.deviceSecret(), ip);
        return new AuthDtos.TokenResponse(mint(playerId), ttl.toSeconds());
    }

    /**
     * The token carries the player id as its subject and nothing else that matters.
     *
     * <p>No device id, no display name: a token is read by anyone who intercepts it, so it
     * should state the minimum needed to serve a request. The subject is enough, and it is
     * what lets every later request skip the device lookup entirely.</p>
     *
     * <p>{@code expiresAt} is the whole reason this is short-lived. The device secret never
     * travels again after this call, and a token that leaks stops working on its own.</p>
     */
    private String mint(UUID playerId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("spacesurvivors")
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(playerId.toString())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
