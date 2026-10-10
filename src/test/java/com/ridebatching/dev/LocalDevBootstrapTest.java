package com.ridebatching.dev;

import com.ridebatching.auth.Role;
import com.ridebatching.auth.UserAccount;
import com.ridebatching.auth.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalDevBootstrapTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalDevBootstrap.class)
            .withBean(UserAccountRepository.class, () -> accounts)
            .withBean(PasswordEncoder.class, () -> encoder);

    @Test
    void shouldNotBootstrapOutsideLocalProfileEvenWhenEnabled() {
        runner.withPropertyValues("app.local.admin.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(LocalDevBootstrap.class));
    }

    @Test
    void shouldLeaveBootstrapDisabledByDefaultInLocalProfile() {
        localRunner().run(context -> assertThat(context).doesNotHaveBean(LocalDevBootstrap.class));
    }

    @Test
    void shouldRejectEnabledBootstrapWithoutSuppliedCredentials() {
        localRunner().withPropertyValues("app.local.admin.enabled=true",
                        "app.local.admin.username=", "app.local.admin.password=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void shouldEncodeSuppliedPasswordWhenBootstrappingEmptyLocalDatabase() {
        when(encoder.encode("test-only-bootstrap-password")).thenReturn("test-only-encoded-hash");
        localRunner().withPropertyValues("app.local.admin.enabled=true",
                        "app.local.admin.username=test-admin",
                        "app.local.admin.password=test-only-bootstrap-password")
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalDevBootstrap.class);
                    context.getBean(LocalDevBootstrap.class).run();
                    ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class);
                    verify(accounts).save(account.capture());
                    assertThat(account.getValue().getUsername()).isEqualTo("test-admin");
                    assertThat(account.getValue().getPasswordHash()).isEqualTo("test-only-encoded-hash");
                    assertThat(account.getValue().getRole()).isEqualTo(Role.ADMIN);
                });
    }

    private ApplicationContextRunner localRunner() {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"));
    }
}
