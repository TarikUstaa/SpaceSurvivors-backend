package com.tarikusta.spacesurvivors.geo;

import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Optional;

/**
 * An address to an ISO-3166 alpha-2 country code, or null.
 *
 * <p>The lookup is a local file — DB-IP's IP-to-Country Lite database, in MaxMind's
 * {@code .mmdb} format and read into memory at startup — so it costs microseconds and no
 * network call. That matters because this runs inside authentication: a geolocation HTTP API
 * would put a third party, and its timeouts, on the path between a player and their own save,
 * and would hand that third party every player's address while it was at it.</p>
 *
 * <p><b>Optional by design.</b> With no database configured, or a file that cannot be read,
 * every lookup answers null and the application starts and serves normally — {@code country}
 * simply stays NULL, exactly as it was before any of this existed. A developer's machine has
 * no copy of the file and neither does the test suite; neither is a reason to refuse to boot.
 * The file is not in this repository — it is 8MB, it is republished monthly, and the image
 * downloads it at build time instead.</p>
 *
 * <p>Answering null is also the normal case in plenty of working deployments: private and
 * loopback addresses have no country, and neither do some perfectly valid public ones.
 * Callers must treat the country as a hint, never as a fact about a player.</p>
 *
 * <p>Data licensed CC-BY-4.0 by DB-IP — attribution is in README.md.</p>
 */
@Component
public class CountryLookup {

    private static final Logger log = LoggerFactory.getLogger(CountryLookup.class);

    /** Null when no usable database was configured — see the class comment. */
    private final DatabaseReader reader;

    public CountryLookup(@Value("${app.geoip.database:}") String databasePath) {
        this.reader = open(databasePath);
    }

    /**
     * The country behind an address, or null when it cannot be established.
     *
     * @param ip a literal address as {@link com.tarikusta.spacesurvivors.auth.ClientAddress}
     *           produces one; null and unparseable values answer null
     */
    public String of(String ip) {
        if (reader == null || ip == null || ip.isBlank()) {
            return null;
        }
        InetAddress address = parse(ip);
        if (address == null || isNotRoutable(address)) {
            return null;
        }
        try {
            // tryCountry, not country: an address the database does not cover is an ordinary
            // outcome here, and the exception-throwing variant would make it look like a fault.
            Optional<CountryResponse> response = reader.tryCountry(address);
            return response
                    .map(CountryResponse::getCountry)
                    .map(Country::getIsoCode)
                    .orElse(null);
        } catch (IOException | GeoIp2Exception | RuntimeException e) {
            // Debug, not warn: this runs per sign-in, and a database that upsets one address
            // would otherwise fill the log with a line nobody can act on.
            log.debug("country lookup failed", e);
            return null;
        }
    }

    /** True for the addresses that cannot belong to a country: loopback, LAN, link-local. */
    private static boolean isNotRoutable(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }

    /**
     * Never resolves a hostname. {@code ClientAddress} has already validated what reaches
     * here, and {@link InetAddress#getByName} only avoids DNS for a literal — so anything
     * that is not one is refused rather than looked up.
     */
    private static InetAddress parse(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            return null;
        }
    }

    private static DatabaseReader open(String databasePath) {
        if (databasePath == null || databasePath.isBlank()) {
            log.info("no GeoIP database configured — player country will stay unset");
            return null;
        }
        File file = new File(databasePath);
        if (!file.isFile()) {
            log.warn("GeoIP database '{}' not found — player country will stay unset", databasePath);
            return null;
        }
        try {
            // Loaded into memory rather than memory-mapped: the file is a few megabytes, the
            // container has no other use for the page cache, and this leaves no open handle on
            // a path that a future image rebuild may replace underneath a running process.
            DatabaseReader opened = new DatabaseReader.Builder(file)
                    .withCache(new CHMCache())
                    .fileMode(Reader.FileMode.MEMORY)
                    .build();
            log.info("GeoIP database loaded from {}", databasePath);
            return opened;
        } catch (IOException e) {
            log.warn("GeoIP database '{}' could not be read — player country will stay unset: {}",
                    databasePath, e.getMessage());
            return null;
        }
    }

    @PreDestroy
    void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }
}
