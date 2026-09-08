package com.tarikusta.spacesurvivors.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that needs the whole application and a real database.
 *
 * <p>A composed annotation rather than a base class, so a test spends nothing to use it —
 * Java allows one superclass, and giving that away for test plumbing is a bad trade. Spring
 * treats the annotations below as if they had been written on the test itself.</p>
 *
 * <p><b>Why {@code @ActiveProfiles("test")} matters more than it looks.</b> The application
 * defaults to the {@code local} profile, which pulls in {@code application-local.properties}
 * — the git-ignored file holding this machine's database password and signing key. Selecting
 * the {@code test} profile instead replaces that file with {@code application-test.properties},
 * which is committed and holds throwaway values. That is the second half of making the suite
 * portable: the container removes the need for a database, and this removes the need for a
 * file that only exists on one laptop.</p>
 *
 * <p><b>One container, not one per test.</b> Spring caches an application context and reuses
 * it for every test whose configuration matches, and the container is a bean in that context,
 * so all of these share a single Postgres. A test that adds {@code @TestPropertySource} asks
 * for a different configuration and therefore gets its own context and its own container —
 * which is worth knowing before adding one casually to a class that does not need it.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@Import(TestDatabase.class)
@ActiveProfiles("test")
public @interface DatabaseTest {
}
