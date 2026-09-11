package com.tarikusta.spacesurvivors.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API's own description, served at {@code /v3/api-docs} and browsable at
 * {@code /swagger-ui.html}.
 *
 * <p>Generated from the controllers rather than written by hand, so it cannot drift from
 * the code the way a separate document does. The one thing that does have to be declared
 * is the credential: the endpoints take it through a filter, not a method parameter, so
 * nothing in a controller signature reveals that every call needs an {@code Authorization}
 * header — without this, the browsable docs would let you try a request and get a
 * confusing 401.</p>
 *
 * <p>Not served in the deployment: {@code springdoc.api-docs.enabled} is false in the prod
 * profile, so this describes the API to whoever is working on it, not to the internet.</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME = "bearerAuth";

    @Bean
    public OpenAPI spaceSurvivorsApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SpaceSurvivors backend")
                        .version("v1")
                        .description("""
                                Cloud save and leaderboards for the SpaceSurvivors game.

                                Every endpoint except `/health` and `POST /v1/auth/token` needs
                                `Authorization: Bearer <token>`.

                                A client gets that token by posting its device id and device secret
                                to `/v1/auth/token`; the server checks the secret against a BCrypt
                                hash and signs a token that is good for an hour. The device secret
                                itself is sent nowhere else.

                                To try a request here: call `/v1/auth/token`, copy the `token` from
                                the response, click **Authorize** and paste it.
                                """))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME))
                .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("""
                                A signed access token from POST /v1/auth/token. Expires after an
                                hour; the client re-authenticates with its device secret.""")));
    }
}
