package com.shop.common.messaging.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Mỗi giờ xóa outbox_event đã PUBLISHED quá hạn giữ (mặc định 3 ngày). */
public class OutboxCleanupJob {

    public static final String LOCK_NAME = "outbox.cleanup";

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);
    private static final int DELETE_BATCH = 1000;

    private final OutboxRepository repository;
    private final OutboxProperties props;

    public OutboxCleanupJob(OutboxRepository repository, OutboxProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @Scheduled(cron = "${shop.outbox.cleanup-cron:0 7 * * * *}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        int total = 0;
        int deleted;
        do {
            deleted = repository.deletePublishedOlderThan(props.retention(), DELETE_BATCH);
            total += deleted;
        } while (deleted == DELETE_BATCH);
        if (total > 0) {
            log.info("Outbox cleanup deleted {} published events older than {}", total, props.retention());
        }
    }
}
