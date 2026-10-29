package com.shop.inventory.infrastructure.redis;

import com.shop.inventory.application.reservation.Item;
import com.shop.inventory.application.reservation.StockCounter;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * StockCounter trên Redis (docs/04 §4 L5, §5).
 *
 * StringRedisTemplate: key/ARGV là chuỗi thô ("5"), không phải JSON ("\"5\"") như RedisTemplate mặc định của v1
 * → Lua tonumber() đọc được. RedisScript tạo 1 lần: Spring gửi EVALSHA, chỉ gửi lại nội dung script khi Redis
 * chưa có (NOSCRIPT).
 *
 * Redis Cluster: script nhiều key yêu cầu các key cùng slot (hash tag, vd inv:stock:{p}:id). Standalone và
 * Sentinel (2 chế độ của deploy/) không có ràng buộc này.
 */
@Component
class RedisStockCounter implements StockCounter {

    static final String KEY_PREFIX = "inv:stock:";
    static final String SUSPECT_PREFIX = "inv:drift:";

    private static final Logger log = LoggerFactory.getLogger(RedisStockCounter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DEDUCT = RedisScript.of(new ClassPathResource("lua/deduct.lua"), List.class);
    private static final RedisScript<Long> ADJUST = RedisScript.of(new ClassPathResource("lua/adjust_if_exists.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final Duration suspectTtl;

    RedisStockCounter(StringRedisTemplate redis, @Value("${inventory.reconcile.interval:5m}") Duration reconcileInterval) {
        this.redis = redis;
        this.suspectTtl = reconcileInterval.multipliedBy(2);
    }

    static String key(long productId) {
        return KEY_PREFIX + productId;
    }

    @Override
    public Deduction tryDeduct(List<Item> items) {
        List<String> keys = items.stream().map(it -> key(it.productId())).toList();
        Object[] qty = items.stream().map(it -> String.valueOf(it.quantity())).toArray();
        List<?> r = redis.execute(DEDUCT, keys, qty);
        long code = ((Number) r.get(0)).longValue();
        int index = ((Number) r.get(1)).intValue();
        if (code == 1) {
            return new Deduction(DeductResult.OK, 0);
        }
        long productId = items.get(index - 1).productId();
        return new Deduction(code == -2 ? DeductResult.INSUFFICIENT : DeductResult.NOT_LOADED, productId);
    }

    @Override
    public void restore(List<Item> items) {
        items.forEach(it -> adjust(it.productId(), it.quantity()));
    }

    @Override
    public void adjust(long productId, int delta) {
        try {
            redis.execute(ADJUST, List.of(key(productId)), String.valueOf(delta));
        } catch (RuntimeException e) {
            log.warn("Redis counter adjust failed for product {} (delta {}), reconcile will fix: {}", productId, delta, e.getMessage());
        }
    }

    @Override
    public void loadIfAbsent(long productId, int available) {
        Boolean set = redis.opsForValue().setIfAbsent(key(productId), String.valueOf(available));
        if (Boolean.TRUE.equals(set)) {
            log.debug("Loaded stock counter for product {} = {}", productId, available);
        }
    }

    @Override
    public OptionalLong current(long productId) {
        String v = redis.opsForValue().get(key(productId));
        return v == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(v));
    }

    @Override
    public void overwrite(long productId, int available) {
        redis.opsForValue().set(key(productId), String.valueOf(available));
    }

    @Override
    public boolean markSuspect(long productId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(SUSPECT_PREFIX + productId, "1", suspectTtl));
    }

    @Override
    public void clearSuspect(long productId) {
        redis.delete(SUSPECT_PREFIX + productId);
    }
}
