package com.shop.common.autoconfigure;

import com.shop.common.persistence.SecurityAuditorAware;
import com.shop.common.security.ShopJwtAuthoritiesConverter;
import com.shop.common.web.error.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Điểm vào auto-configuration của shop-common.
 * Đăng ký trong META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports:
 * service có shop-common trên classpath là tự nạp, không cần @Enable... hay @ComponentScan.
 *
 * Mỗi nhóm bean nằm trong 1 class con có điều kiện riêng, để service không dùng JPA/Web
 * vẫn dùng được shop-common mà không lỗi thiếu class.
 */
@AutoConfiguration(afterName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
public class ShopCommonAutoConfiguration {

    /**
     * Resource server tự nhặt bean JwtAuthenticationConverter này (kể cả khi service tự khai báo
     * SecurityFilterChain với oauth2ResourceServer(o -> o.jwt(withDefaults()))).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter")
    static class JwtSecurityConfiguration {

        @Bean
        @ConditionalOnMissingBean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(new ShopJwtAuthoritiesConverter());
            return converter;
        }
    }

    /** Lỗi trả về dạng RFC 9457 ProblemDetail, thống nhất cho mọi service. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
    static class WebErrorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        GlobalExceptionHandler shopGlobalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    /**
     * Tự điền created_by/updated_by cho entity kế thừa AuditableEntity.
     * Service KHÔNG tự thêm @EnableJpaAuditing nữa (sẽ trùng bean).
     * Chỉ bật khi JPA đã thật sự được cấu hình (có EntityManagerFactory); nếu không,
     * @EnableJpaAuditing làm app sập với "JPA metamodel must not be empty".
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "org.springframework.data.jpa.domain.support.AuditingEntityListener",
        "org.springframework.security.core.context.SecurityContextHolder"
    })
    @ConditionalOnBean(type = "jakarta.persistence.EntityManagerFactory")
    @EnableJpaAuditing(auditorAwareRef = "shopAuditorAware")
    static class JpaAuditingConfiguration {

        @Bean
        AuditorAware<String> shopAuditorAware() {
            return new SecurityAuditorAware();
        }
    }
}
