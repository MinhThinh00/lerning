package com.shop.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.shop.common.web.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class ShopCommonAutoConfigurationTest {

    private static final AutoConfigurations CONFIG = AutoConfigurations.of(ShopCommonAutoConfiguration.class);

    @Test
    void registersExceptionHandlerInServletApp() {
        new WebApplicationContextRunner()
            .withConfiguration(CONFIG)
            .run(ctx -> assertThat(ctx).hasSingleBean(GlobalExceptionHandler.class));
    }

    @Test
    void skipsExceptionHandlerOutsideWebApp() {
        new ApplicationContextRunner()
            .withConfiguration(CONFIG)
            .run(ctx -> assertThat(ctx).doesNotHaveBean(GlobalExceptionHandler.class));
    }

    @Test
    void skipsJpaAuditingWithoutEntityManagerFactory() {
        new ApplicationContextRunner()
            .withConfiguration(CONFIG)
            .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean("shopAuditorAware"));
    }
}
