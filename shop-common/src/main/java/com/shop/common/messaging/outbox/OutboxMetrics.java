package com.shop.common.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.LongSupplier;

/**
 * Gauge cho alert OutboxBacklog (docs/05-MESSAGING.md §4.4). Giá trị được query mỗi lần scrape.
 * DB lỗi → NaN, để Prometheus thấy "không có số liệu" thay vì một con số 0 giả.
 */
public class OutboxMetrics implements MeterBinder {

    private final OutboxRepository repository;

    public OutboxMetrics(OutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.pending.events", repository, r -> safe(() -> r.countPending()))
            .description("Outbox events waiting to be published")
            .register(registry);
        Gauge.builder("outbox.oldest.pending.age.seconds", repository, r -> safe(() -> r.oldestPendingAgeSeconds()))
            .description("Age of the oldest unpublished outbox event")
            .baseUnit("seconds")
            .register(registry);
    }

    private static double safe(LongSupplier query) {
        try {
            return query.getAsLong();
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }
}
