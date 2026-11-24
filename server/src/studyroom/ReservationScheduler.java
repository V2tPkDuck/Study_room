package studyroom;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 30-second sweep: missed check-ins become PENDING, expired check-ins complete. Demo interval; see the system guide. */
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
            LoggerFactory.getLogger(getClass()).error("Auto-fulfilment sweep failed; retrying on next tick", ex);
        }
    }
}
