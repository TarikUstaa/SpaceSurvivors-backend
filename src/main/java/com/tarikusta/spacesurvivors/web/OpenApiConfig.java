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
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME = "deviceAuth";

    @Bean
    public OpenAPI spaceSurvivorsApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SpaceSurvivors backend")
                        .version("v1")
                        .description("""
                                Cloud save and leaderboards for the SpaceSurvivors game.

                                Identity travels as `Authorization: Device <device-id>`. That is
                                identification, not authentication — the server believes whatever
                                device id it is sent. Verified tokens replace the scheme later.
                                """))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME))
                .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("Device")
                        .description("The device id generated once by the client and stored on the device.")));
    }
}
