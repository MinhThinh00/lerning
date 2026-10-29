package com.shop.common.messaging.inbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Mỗi ngày xóa processed_event quá hạn giữ (mặc định 14 ngày). */
public class InboxCleanupJob {

    public static final String LOCK_NAME = "inbox.cleanup";

    private static final Logger log = LoggerFactory.getLogger(InboxCleanupJob.class);
    private static final int DELETE_BATCH = 1000;

    private final InboxGuard inbox;
    private final InboxProperties props;

    public InboxCleanupJob(InboxGuard inbox, InboxProperties props) {
        this.inbox = inbox;
        this.props = props;
    }

    @Scheduled(cron = "${shop.inbox.cleanup-cron:0 17 3 * * *}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        int total = 0;
        int deleted;
        do {
            deleted = inbox.deleteOlderThan(props.retention(), DELETE_BATCH);
            total += deleted;
        } while (deleted == DELETE_BATCH);
        if (total > 0) {
            log.info("Inbox cleanup deleted {} processed events older than {}", total, props.retention());
        }
    }
}
