package studyroom.booking;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

/**
 * Booking-slice request payloads. Field limits mirror database column sizes.
 */
public final class BookingRequests {
    private BookingRequests() {
    }

    public record Booking(@Positive long seatId, @NotNull LocalDateTime startAt, @NotNull LocalDateTime endAt) {
    }

    /**
     * Shared reason payload for administrator reservation actions: confirm and
     * dismiss.
     */
    public record Reason(@NotBlank @Size(max = 300) String reason) {
    }

    /** Manual violation entry; V-01 counts violations and no longer uses points. */
    public record Violation(@Positive long reservationId, @NotBlank @Size(max = 300) String reason) {
    }
}