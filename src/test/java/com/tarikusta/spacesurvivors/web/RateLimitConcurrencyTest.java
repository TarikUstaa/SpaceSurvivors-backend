package com.tarikusta.spacesurvivors.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the ceiling holds when the requests arrive at once, which is how they would arrive.
 *
 * <p>A limiter is only ever tested seriously by concurrency: the whole point is the moment
 * many requests land together, and that is exactly the moment a counter is easiest to get
 * wrong. Two failures would be invisible to the single-threaded tests next door — buckets
 * created per racing thread instead of shared, so each attacker thread gets its own
 * allowance; or a token counted twice because two threads read the same balance before
 * either wrote it back.</p>
 *
 * <p>This is the shape of bug D13 was: code that looked obviously correct read one request
 * at a time, and was dead wrong the first time two arrived together.</p>
 */
class RateLimitConcurrencyTest {

    private static final int CAPACITY = 5;
    private static final int THREADS = 64;

    @Test
    @DisplayName("sixty-four simultaneous callers cannot spend more than the bucket holds")
    void concurrentBurstCannotExceedTheCapacity() throws Exception {
        RateLimitFilter filter = new RateLimitConfig().rateLimitFilterRegistration(
                new RateLimitProperties(true, CAPACITY, Duration.ofMinutes(1), 1_000, CAPACITY),
                new ObjectMapper()).getFilter();

        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                pool.execute(() -> {
                    try {
                        // Every thread waits here, so the requests are genuinely simultaneous
                        // rather than merely fast — a staggered burst would not exercise the race.
                        startTogether.await();

                        MockHttpServletRequest request =
                                new MockHttpServletRequest("POST", RateLimitFilter.GUARDED_PATH);
                        request.setRemoteAddr("10.0.0.1");           // one caller, many threads
                        MockHttpServletResponse response = new MockHttpServletResponse();

                        filter.doFilter(request, response, new MockFilterChain());
                        if (response.getStatus() != HttpStatus.TOO_MANY_REQUESTS.value()) {
                            allowed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startTogether.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        }

        // Exactly the capacity, allowing one for a token that may refill mid-burst. If the
        // bucket store were not atomic, racing threads would each build their own bucket and
        // this would come back at or near THREADS.
        assertThat(allowed.get())
                .as("a simultaneous burst must not buy more than a sequential one")
                .isBetween(CAPACITY, CAPACITY + 1);
    }

    @Test
    @DisplayName("separate callers hitting at once keep separate allowances")
    void concurrentDistinctCallersDoNotShareABucket() throws Exception {
        RateLimitFilter filter = new RateLimitConfig().rateLimitFilterRegistration(
                new RateLimitProperties(true, CAPACITY, Duration.ofMinutes(1), 1_000, CAPACITY),
                new ObjectMapper()).getFilter();

        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                int caller = i;
                pool.execute(() -> {
                    try {
                        startTogether.await();
                        MockHttpServletRequest request =
                                new MockHttpServletRequest("POST", RateLimitFilter.GUARDED_PATH);
                        request.setRemoteAddr("10.1." + (caller / 256) + "." + (caller % 256));
                        MockHttpServletResponse response = new MockHttpServletResponse();

                        filter.doFilter(request, response, new MockFilterChain());
                        if (response.getStatus() != HttpStatus.TOO_MANY_REQUESTS.value()) {
                            allowed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startTogether.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        }

        // One request each, all from different addresses: nobody should be refused. A shared
        // or colliding bucket would show up here as a handful of spurious 429s.
        assertThat(allowed.get()).isEqualTo(THREADS);
    }
}
