package com.tarikusta.spacesurvivors.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When the first administrator is created, and — mostly — when it is not.
 *
 * <p>A unit test, with a mocked repository and a real {@link BCryptPasswordEncoder}. The
 * repository is mocked because none of the decisions here are about the database: the only
 * thing this class asks it is "is the table empty", and the interesting behaviour is
 * everything it declines to do with the answer. The encoder is real because the one
 * assertion that would be worthless against a fake is the one that matters most — that what
 * gets stored is a hash and not the password.</p>
 */
class AdminBootstrapTest {

    private static final String USERNAME = "operator";
    private static final String PASSWORD = "a-long-enough-password";

    private final AdminUserRepository admins = mock(AdminUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private void run(String username, String password, long existingAdmins) {
        when(admins.count()).thenReturn(existingAdmins);
        new AdminBootstrap(admins, encoder, username, password)
                .run(new DefaultApplicationArguments());
    }

    @Test
    @DisplayName("creates the account when there is none, and stores a hash")
    void createsTheFirstAdmin() {
        run(USERNAME, PASSWORD, 0);

        ArgumentCaptor<AdminUser> saved = ArgumentCaptor.forClass(AdminUser.class);
        verify(admins).save(saved.capture());

        AdminUser created = saved.getValue();
        assertThat(created.getUsername()).isEqualTo(USERNAME);
        assertThat(created.getRole()).isEqualTo("ADMIN");
        assertThat(created.isEnabled()).isTrue();

        // Both halves matter. The first would pass against a fake encoder that returned the
        // password unchanged; the second is what actually says "this is not the password".
        assertThat(encoder.matches(PASSWORD, created.getPasswordHash())).isTrue();
        assertThat(created.getPasswordHash()).isNotEqualTo(PASSWORD);
    }

    @Test
    @DisplayName("does nothing when an administrator already exists")
    void leavesAnExistingAdminAlone() {
        // The important case. If this ever started overwriting, a password changed by hand
        // would silently revert to the environment's value on the next restart — and the
        // environment variable is the one more people can read.
        run(USERNAME, PASSWORD, 1);

        verify(admins, never()).save(any());
    }

    @Test
    @DisplayName("does nothing, and does not fail, when the settings are absent")
    void toleratesMissingSettings() {
        run("", "", 0);
        verify(admins, never()).save(any());
    }

    @Test
    @DisplayName("refuses a password too short to be worth hashing")
    void refusesAShortPassword() {
        run(USERNAME, "short", 0);
        verify(admins, never()).save(any());
    }

    @Test
    @DisplayName("refuses a username the database would reject anyway")
    void refusesAShortUsername() {
        // The CHECK constraint in V3 says 3-32. Catching it here turns a constraint
        // violation at startup into a log line that says what to fix.
        run("ab", PASSWORD, 0);
        verify(admins, never()).save(any());
    }

    @Test
    @DisplayName("trims the username, so a stray space in an environment variable is not a new account")
    void trimsTheUsername() {
        run("  " + USERNAME + "  ", PASSWORD, 0);

        ArgumentCaptor<AdminUser> saved = ArgumentCaptor.forClass(AdminUser.class);
        verify(admins).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo(USERNAME);
    }
}
