package com.shop.inventory.config;

import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RedissonClient (lock cho L4) đọc CÙNG cấu hình {@code spring.data.redis.*} với Lettuce/StringRedisTemplate:
 * đổi standalone ↔ sentinel chỉ bằng env (deploy/infra/redis/compose.yaml). Dùng redisson core thay cho
 * redisson-spring-boot-starter (starter chưa theo kịp Boot 4).
 *
 * lazyInitialization: không kết nối lúc khởi động → Redis chưa chạy app vẫn lên được (L1–L3 không cần Redis,
 * L5 tự rơi về DB); L4 thì lỗi tới khi Redis sống lại.
 */
@Configuration(proxyBeanMethods = false)
class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(DataRedisProperties redis) {
        Config config = new Config().setLazyInitialization(true);
        int timeout = millis(redis.getTimeout(), 3000);
        int connectTimeout = millis(redis.getConnectTimeout(), 3000);
        String password = redis.getPassword() == null || redis.getPassword().isBlank() ? null : redis.getPassword();

        DataRedisProperties.Sentinel sentinel = redis.getSentinel();
        if (sentinel != null && sentinel.getMaster() != null) {
            SentinelServersConfig s = config.useSentinelServers()
                .setMasterName(sentinel.getMaster())
                .setDatabase(redis.getDatabase());
            sentinel.getNodes().forEach(node -> s.addSentinelAddress("redis://" + node));
            s.setPassword(password).setTimeout(timeout).setConnectTimeout(connectTimeout);
        } else {
            SingleServerConfig s = config.useSingleServer()
                .setAddress("redis://" + redis.getHost() + ":" + redis.getPort())
                .setDatabase(redis.getDatabase());
            s.setPassword(password).setTimeout(timeout).setConnectTimeout(connectTimeout);
        }
        return Redisson.create(config);
    }

    private static int millis(Duration d, int fallback) {
        return d == null ? fallback : (int) d.toMillis();
    }
}
