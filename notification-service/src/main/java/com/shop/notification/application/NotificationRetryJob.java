package com.shop.notification.application;

import com.shop.notification.config.NotificationProperties;
import com.shop.notification.domain.NotificationLogRepository;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thử gửi lại thông báo PENDING (lỗi tạm thời lần trước) và SENDING bị bỏ dở (worker chết giữa chừng).
 * ShedLock để N replica không cùng quét; kể cả khi quét trùng, claim vẫn đảm bảo mỗi dòng chỉ 1 worker gửi.
 */
@Component
public class NotificationRetryJob {

    public static final String LOCK_NAME = "notification.retry";

    private static final int PAGE = 100;

    private final NotificationLogRepository logs;
    private final NotificationDispatcher dispatcher;
    private final NotificationProperties props;

    public NotificationRetryJob(NotificationLogRepository logs, NotificationDispatcher dispatcher, NotificationProperties props) {
        this.logs = logs;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${notification.retry-interval:30s}", initialDelayString = "${notification.retry-interval:30s}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void retry() {
        runOnce();
    }

    /** Một lượt, KHÔNG qua ShedLock (test gọi trực tiếp). */
    public void runOnce() {
        Instant now = Instant.now();
        List<Long> ids = logs.findDueIds(now.minus(props.retryAfter()), now.minus(props.claimTimeout()), PageRequest.of(0, PAGE));
        ids.forEach(dispatcher::dispatch);
    }
}
