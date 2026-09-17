package com.tarikusta.spacesurvivors.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Forgets a player's sign-in address once they have been away long enough.
 *
 * <p><b>Why.</b> {@code player_profile.last_ip} is personal data, kept for two reasons: resolving a
 * country, and letting an administrator ask "is this the same person as that account". Both are
 * about players who are around. For somebody who has not signed in for {@code N} days the address
 * answers neither question well and is still personal data — so it goes. The country it produced
 * stays: a two-letter code is not an address.</p>
 *
 * <p><b>When it runs, and why twice.</b> Daily on a schedule, and once at every start. The
 * deployment scales to zero, and a container that is not running at 03:17 UTC does not run a job
 * scheduled for then; one that starts because somebody opened the game does. Between the two, the
 * clearing happens at least as often as the service is used, which is the only time it matters.
 * The statement is idempotent — a second run in a day finds nothing — so running it more than
 * needed costs one indexed-ish scan of a small table.</p>
 *
 * <p><b>It must not look like the player came back.</b> {@code updated_at} is "last seen" and the
 * trigger would set it to now — on exactly the players who have been gone longest, resetting the
 * very clock this job measures by. The transaction sets {@code app.preserve_updated_at} first; see
 * V7__preserve_last_seen.sql.</p>
 *
 * <p>A failure is logged and swallowed. Starting the service matters more than finishing a
 * clean-up that will be tried again tomorrow.</p>
 */
@Component
public class IpRetention implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IpRetention.class);

    private final JdbcClient db;
    private final TransactionTemplate transaction;
    private final int days;

    public IpRetention(JdbcClient db, TransactionTemplate transaction,
                       @Value("${app.privacy.ip-retention-days:90}") int days) {
        this.db = db;
        this.transaction = transaction;
        this.days = days;
    }

    @Override
    public void run(ApplicationArguments args) {
        clearQuietly();
    }

    @Scheduled(cron = "${app.privacy.ip-retention-cron:0 17 3 * * *}", zone = "UTC")
    public void daily() {
        clearQuietly();
    }

    /**
     * Clear every address last used more than the retention period ago.
     *
     * <p>Through a {@link TransactionTemplate} rather than {@code @Transactional}: the two callers
     * above are methods of this same object, and a call from inside a bean does not pass through
     * the proxy that would start the transaction — the setting and the update would then run in
     * separate transactions, and the setting would protect nothing.</p>
     *
     * @return how many addresses were cleared
     */
    public int clear() {
        Integer cleared = transaction.execute(status -> {
            db.sql("SELECT set_config('app.preserve_updated_at', 'on', true)").query(String.class).single();
            return db.sql("""
                            UPDATE player_profile
                               SET last_ip = NULL
                             WHERE last_ip IS NOT NULL
                               AND updated_at < now() - make_interval(days => :days)""")
                    .param("days", days)
                    .update();
        });
        int count = cleared == null ? 0 : cleared;
        if (count > 0) {
            log.info("cleared the sign-in address of {} players not seen for {} days", count, days);
        }
        return count;
    }

    public int retentionDays() {
        return days;
    }

    private void clearQuietly() {
        try {
            clear();
        } catch (RuntimeException e) {
            log.warn("could not clear old sign-in addresses; will try again on the next run", e);
        }
    }
}
