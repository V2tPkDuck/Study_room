package studyroom;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 30 秒巡检：未签到转待核查，已签到到期自动完成。演示参数，见 docs/系统说明.md。 */
@Component
@ConditionalOnProperty(name="app.scheduling-enabled", havingValue="true", matchIfMissing=true)
public class ReservationScheduler {
    private final BookingService bookings;

    public ReservationScheduler(BookingService bookings) {
        this.bookings = bookings;
    }

    @Scheduled(initialDelay=5000, fixedDelayString="${app.sweep-delay-ms}")
    public void tick() {
        try {
            bookings.sweep();
        } catch (Exception ex) {
            LoggerFactory.getLogger(getClass()).error("自动履约任务失败，下轮重试", ex);
        }
    }
}
