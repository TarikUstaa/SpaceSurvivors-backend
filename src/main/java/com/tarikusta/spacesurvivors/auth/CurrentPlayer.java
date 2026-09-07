package com.tarikusta.spacesurvivors.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller parameter as the authenticated player's id, taken from the token.
 *
 * <p>The value is not a request parameter and must never be one — a caller supplying their
 * own player id is exactly what authentication exists to prevent. It comes from the signed
 * token and nowhere else.</p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentPlayer {
}
