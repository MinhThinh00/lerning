package com.shop.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.shop.common.messaging.Topics;
import com.shop.common.messaging.it.KafkaTestSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Lớp cha cho integration test của inventory-service: app thật + MySQL + Kafka thật (cần Docker).
 * Mọi lớp con dùng chung 1 cấu hình → Spring chỉ dựng 1 context cho cả bộ test.
 *
 * Topic tạo trước giống job kafka-init (deploy/infra/kafka/topics.sh): code không tự tạo topic.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class InventoryIntegrationTest {

    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36").withDatabaseName("db_inventory");
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    private static final List<String> TOPICS = List.of(Topics.ORDER_CREATED, Topics.ORDER_PAID, Topics.ORDER_CANCELLED,
        Topics.INVENTORY_RESERVED, Topics.INVENTORY_FAILED);

    /** productId riêng cho mỗi test, tránh đụng seed 1..10 và nhau. */
    private static final AtomicLong NEXT_PRODUCT_ID = new AtomicLong(System.currentTimeMillis() % 1_000_000 * 1000);

    static {
        Startables.deepStart(MYSQL, KAFKA).join();
        TOPICS.forEach(t -> KafkaTestSupport.createTopics(KAFKA.getBootstrapServers(), 3, t, Topics.dlt(t)));
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        // giống compose: innodb_lock_wait_timeout ở mức session
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?sessionVariables=innodb_lock_wait_timeout=3");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.properties.metadata.max.age.ms", () -> "1000");
        registry.add("shop.outbox.poll-interval-ms", () -> "100");
    }

    @MockitoBean
    JwtDecoder jwtDecoder;   // không gọi Keycloak lúc test

    @Autowired
    protected JdbcClient jdbc;

    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /** Tạo dòng tồn kho mới với số lượng cho trước, trả về productId. */
    protected long newProduct(int available) {
        long productId = NEXT_PRODUCT_ID.incrementAndGet();
        jdbc.sql("INSERT INTO inventory (product_id, available, created_at, updated_at) VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")
            .params(productId, available).update();
        return productId;
    }

    protected Map<String, Object> stock(long productId) {
        return jdbc.sql("SELECT available, reserved, sold FROM inventory WHERE product_id = ?").param(productId)
            .query().singleRow();
    }

    protected int column(long productId, String name) {
        return ((Number) stock(productId).get(name)).intValue();
    }

    protected List<String> reservationStatuses(String orderId) {
        return jdbc.sql("SELECT status FROM inventory_reservation WHERE order_id = ? ORDER BY product_id")
            .param(orderId).query(String.class).list();
    }

    /**
     * Bất biến docs/04 §2:
     *   available + reserved + sold = tổng nhập kho
     *   reserved = SUM(quantity) của reservation RESERVED
     *   không cột nào âm
     */
    protected void assertInvariant(long productId, int totalStock) {
        int available = column(productId, "available");
        int reserved = column(productId, "reserved");
        int sold = column(productId, "sold");
        int reservedByRows = jdbc.sql("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_reservation WHERE product_id = ? AND status = 'RESERVED'
                """).param(productId).query(Integer.class).single();
        assertThat(available + reserved + sold).as("available + reserved + sold").isEqualTo(totalStock);
        assertThat(reserved).as("reserved = SUM(RESERVED rows)").isEqualTo(reservedByRows);
        assertThat(available).as("available").isNotNegative();
    }
}
