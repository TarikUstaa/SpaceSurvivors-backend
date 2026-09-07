package com.tarikusta.spacesurvivors.auth;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/**
 * Turns the verified token into the {@code UUID} a controller asked for.
 *
 * <p>Spring Security has already checked the signature and the expiry by the time this
 * runs — an unauthenticated request never reaches a controller. So the subject claim can
 * be trusted, and no database lookup is needed to know who is calling: the token carries
 * the player id, which is why authenticated requests no longer resolve a device on every
 * call the way they did before.</p>
 */
public class CurrentPlayerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentPlayer.class)
                && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public UUID resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                NativeWebRequest request, WebDataBinderFactory binder) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            // Unreachable through the filter chain; a programming error if it ever happens.
            throw new IllegalStateException("no authenticated player on the request");
        }
        return UUID.fromString(jwt.getSubject());
    }
}
