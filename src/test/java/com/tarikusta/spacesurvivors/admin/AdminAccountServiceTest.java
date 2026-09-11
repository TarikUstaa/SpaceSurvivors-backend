package com.tarikusta.spacesurvivors.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules of changing a password, without a browser in the way.
 *
 * <p>The encoder is a real {@link BCryptPasswordEncoder}. That is not incidental: a fake one
 * that returned the password unchanged would let every assertion below pass while the service
 * stored plaintext, and the salting behaviour it has — the same password encoding to a
 * different string every time — is exactly what the service has to be written around.</p>
 */
class AdminAccountServiceTest {

    private static final String NAME = "operator";
    private static final String CURRENT = "the-current-password";

    private static final String IP = "203.0.113.9";

    private final AdminUserRepository admins = mock(AdminUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AdminAudit audit = mock(AdminAudit.class);
    private final AdminAccountService service =
            new AdminAccountService(admins, encoder, audit, 12);

    private AdminUser admin;

    @BeforeEach
    void existingAdmin() {
        admin = new AdminUser(NAME, encoder.encode(CURRENT), "ADMIN");
        when(admins.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(admins.findByUsernameIgnoreCase(NAME)).thenReturn(Optional.of(admin));
    }

    @Test
    @DisplayName("the new password replaces the old one, and the old one stops working")
    void changesThePassword() {
        String before = admin.getPasswordHash();

        AdminAccountService.Result result =
                service.changePassword(NAME, CURRENT, "a-brand-new-password", IP);

        assertThat(result).isEqualTo(AdminAccountService.Result.CHANGED);
        verify(admins).save(admin);

        // Three separate claims, and the middle one is the one a careless implementation
        // gets right by accident: the stored value changed, the new password verifies, and
        // the old password no longer does.
        assertThat(admin.getPasswordHash()).isNotEqualTo(before);
        assertThat(encoder.matches("a-brand-new-password", admin.getPasswordHash())).isTrue();
        assertThat(encoder.matches(CURRENT, admin.getPasswordHash())).isFalse();

        // The change and the record of it are one unit; a change nobody can account for
        // is the shape of an account quietly taken over.
        verify(audit).passwordChanged(NAME, IP);
    }

    @Test
    @DisplayName("a wrong current password changes nothing")
    void refusesAWrongCurrentPassword() {
        String before = admin.getPasswordHash();

        AdminAccountService.Result result =
                service.changePassword(NAME, "not-it", "a-brand-new-password", IP);

        assertThat(result).isEqualTo(AdminAccountService.Result.WRONG_CURRENT_PASSWORD);
        assertThat(admin.getPasswordHash()).isEqualTo(before);
        verify(admins, never()).save(any());

        // Nothing changed, so nothing is recorded. An audit table that logs attempts as
        // though they were events is read as one and lies.
        verify(audit, never()).passwordChanged(anyString(), anyString());
    }

    @Test
    @DisplayName("a missing current password is a wrong one, not a crash")
    void treatsNullAsWrong() {
        assertThat(service.changePassword(NAME, null, "a-brand-new-password", IP))
                .isEqualTo(AdminAccountService.Result.WRONG_CURRENT_PASSWORD);
    }

    @Test
    @DisplayName("a new password under the minimum is refused")
    void refusesAShortNewPassword() {
        assertThat(service.changePassword(NAME, CURRENT, "short", IP))
                .isEqualTo(AdminAccountService.Result.TOO_SHORT);
        verify(admins, never()).save(any());
    }

    @Test
    @DisplayName("re-entering the same password is refused rather than reported as a change")
    void refusesTheSamePassword() {
        // Not pedantry. BCrypt salts every hash, so re-encoding the same password produces a
        // different string and the row would genuinely change — the page would say "changed",
        // and it would be true of the database and false of the password.
        assertThat(service.changePassword(NAME, CURRENT, CURRENT, IP))
                .isEqualTo(AdminAccountService.Result.SAME_AS_CURRENT);
        verify(admins, never()).save(any());
    }

    @Test
    @DisplayName("an account that no longer exists is reported, not ignored")
    void handlesAVanishedAccount() {
        assertThat(service.changePassword("ghost", CURRENT, "a-brand-new-password", IP))
                .isEqualTo(AdminAccountService.Result.NO_SUCH_ADMIN);
    }
}
