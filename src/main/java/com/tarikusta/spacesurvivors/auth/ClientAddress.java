package com.tarikusta.spacesurvivors.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * The caller's address, or null when it cannot be established.
 *
 * <p>Always validated before it leaves here: the value reaches a Postgres {@code inet}
 * column, and an unparseable one fails the whole statement. That was a real defect — a
 * single header turned every request into a 500.</p>
 *
 * <p>{@code X-Forwarded-For} is ignored. It means something only when a proxy we run added
 * it; sent straight to the server it is a claim, and believing it let anyone write any
 * address they liked. When something trustworthy sits in front, Spring's
 * {@code ForwardedHeaderFilter} plus {@code server.forward-headers-strategy=framework} is
 * the supported way to honour it, and {@code getRemoteAddr} keeps working unchanged.</p>
 *
 * <p>Public since the backoffice's audit trail needs the same answer, and needs it to have
 * gone through the same validation. A second implementation in another package is how two
 * parts of one application end up disagreeing about what a caller's address is.</p>
 */
public final class ClientAddress {

    /** Longest possible textual IPv6 address. */
    private static final int MAX_LENGTH = 45;

    /** Four decimal groups. Anything else is either IPv6 or not an address at all. */
    private static final Pattern DOTTED_QUAD = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request) {
        return literalOrNull(request.getRemoteAddr());
    }

    /**
     * Parse a literal IPv4/IPv6 address, or return null.
     *
     * <p>Never resolves a hostname: a value containing {@code :} cannot be one, and
     * anything else must match four decimal groups first, so {@link InetAddress#getByName}
     * only ever sees a literal and cannot trigger a DNS lookup.</p>
     */
    private static String literalOrNull(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            return null;
        }
        String candidate = value.trim();

        // fe80::1%eth0 — Postgres' inet type does not accept the zone suffix
        int zone = candidate.indexOf('%');
        if (zone >= 0) {
            candidate = candidate.substring(0, zone);
        }

        boolean literal = candidate.indexOf(':') >= 0 || DOTTED_QUAD.matcher(candidate).matches();
        if (!literal) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
