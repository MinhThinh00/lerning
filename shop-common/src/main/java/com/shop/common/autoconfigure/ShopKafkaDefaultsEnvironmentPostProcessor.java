package com.shop.common.autoconfigure;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.util.ClassUtils;

/**
 * Nạp cấu hình Kafka mặc định (shop-common-kafka-defaults.properties, docs/05-MESSAGING.md §6) với độ ưu tiên
 * THẤP NHẤT: application.yml / env của service luôn ghi đè được. Nhờ vậy service không phải chép lại
 * serializer, acks, ack-mode... và không ai lỡ quay lại JsonSerializer.
 *
 * Đăng ký trong META-INF/spring.factories. Chỉ chạy khi service có Kafka trên classpath.
 */
public class ShopKafkaDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "shopCommonKafkaDefaults";
    private static final String RESOURCE = "shop-common-kafka-defaults.properties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!ClassUtils.isPresent("org.springframework.kafka.core.KafkaTemplate", getClass().getClassLoader())
            || environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        try {
            environment.getPropertySources().addLast(new ResourcePropertySource(SOURCE_NAME, new ClassPathResource(RESOURCE)));
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot load " + RESOURCE, ex);
        }
    }
}
