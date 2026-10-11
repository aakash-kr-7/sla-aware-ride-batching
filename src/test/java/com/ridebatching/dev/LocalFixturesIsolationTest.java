package com.ridebatching.dev;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class LocalFixturesIsolationTest {
    @Test void normalAndLocalAdminProfilesDoNotLoadBusinessFixtures() {
        ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(LocalFixtures.class);
        context.run(c -> assertThat(c).doesNotHaveBean(LocalFixtures.class));
        context.withInitializer(c -> c.getEnvironment().setActiveProfiles("local"))
                .run(c -> assertThat(c).doesNotHaveBean(LocalFixtures.class));
    }
}
