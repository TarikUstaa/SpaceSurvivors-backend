package com.tarikusta.spacesurvivors;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.Test;

/**
 * The smallest useful test there is: the whole application context starts.
 *
 * <p>An empty body is the point. Every bean is constructed, every {@code @Value} is resolved,
 * Flyway runs and Hibernate validates the schema it produced — so a missing property, a
 * circular dependency or an entity that no longer matches its table fails here, once, instead
 * of inside whichever test happened to touch it first.</p>
 */
@DatabaseTest
class SpacesurvivorsApplicationTests {

    @Test
    void contextLoads() {
    }
}
