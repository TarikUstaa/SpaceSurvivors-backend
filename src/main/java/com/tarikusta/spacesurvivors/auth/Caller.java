package com.tarikusta.spacesurvivors.auth;

/**
 * Who is making this request, as far as the transport can tell.
 *
 * <p>Put on the request by {@link DeviceAuthFilter} and handed to controllers with
 * {@code @RequestAttribute(Caller.ATTR)}. Bundling the two values keeps controller
 * signatures to a single caller parameter, and keeps {@code HttpServletRequest}
 * out of the service layer entirely.</p>
 *
 * @param deviceId the client-generated device identifier — how we recognise the player
 * @param ip       the address the request came from, or null when it cannot be determined
 */
public record Caller(String deviceId, String ip) {

    /** Request-attribute key. */
    public static final String ATTR = "caller";
}
