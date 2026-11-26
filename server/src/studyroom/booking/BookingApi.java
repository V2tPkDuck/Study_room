package studyroom.booking;

import jakarta.validation.Valid;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import studyroom.Requests;

/**
 * Reservation endpoints.
 *
 * <p>Responsibilities: room and seat lookup for the booking page, create/cancel/
 * check-in/check-out, personal history, and the administrator's pending-review
 * and sweep actions. All business rules stay in {@link BookingService}; this class
 * only translates HTTP to method calls.
 */
@RestController
@RequestMapping("/api")
public class BookingApi {
    private final SeatQueryService seatQuery;
    private final BookingService bookings;
    private final FulfillmentService fulfillment;

    public BookingApi(SeatQueryService seatQuery, BookingService bookings, FulfillmentService fulfillment) {
        this.seatQuery = seatQuery;
        this.bookings = bookings;
        this.fulfillment = fulfillment;
    }

    /** Returns the current booking policy values (read-only for the client). */
    @GetMapping("/policy")
    Map<String,Object> policy() {
        return seatQuery.policy();
    }

    /** FR-01: enabled rooms shown in the student seat picker. */
    @GetMapping("/rooms")
    List<Map<String,Object>> rooms() {
        return seatQuery.rooms(false);
    }

    /** FR-02/FR-14: seats of one room with their live state for the requested window. */
    @GetMapping("/rooms/{room}/seats")
    List<Map<String,Object>> seats(@PathVariable long room,
                                   @RequestParam(required=false) LocalDateTime start,
                                   @RequestParam(required=false) LocalDateTime end) {
        return seatQuery.seats(room, start, end, false);
    }

    /** FR-07: the caller's reservations, filtered by status/date and paged. */
    @GetMapping("/reservations")
    Map<String,Object> myReservations(@RequestParam(required=false) String status,
                                      @RequestParam(required=false) LocalDate date,
                                      @RequestParam(defaultValue="1") int page,
                                      @RequestParam(defaultValue="20") int size) {
        return bookings.reservations(false, status, date, page, size);
    }

    /** FR-03: create a reservation. Validation order and messages are owned by the service. */
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> createReservation(@Valid @RequestBody Requests.Booking input) {
        return bookings.create(input);
    }

    /** FR-04/05/06: one student action on a reservation: cancel, check-in, check-out. */
    @PostMapping("/reservations/{id}/{action}")
    Map<String,String> reservationAction(@PathVariable long id, @PathVariable String action) {
        bookings.transition(id, action);
        return ok();
    }

    /** FR-15/FR-16: every reservation for the administrator console. */
    @GetMapping("/admin/reservations")
    Map<String,Object> adminReservations(@RequestParam(required=false) String status,
                                         @RequestParam(required=false) LocalDate date,
                                         @RequestParam(defaultValue="1") int page,
                                         @RequestParam(defaultValue="20") int size) {
        return bookings.reservations(true, status, date, page, size);
    }

    /** FR-16: confirm that a PENDING reservation is a violation. */
    @PostMapping("/admin/reservations/{id}/confirm-violation")
    Map<String,String> confirmViolation(@PathVariable long id, @Valid @RequestBody Requests.Reason input) {
        fulfillment.resolvePending(id, true, input.reason());
        return ok();
    }

    /** FR-16: dismiss a PENDING review and release the reservation. */
    @PostMapping("/admin/reservations/{id}/dismiss-pending")
    Map<String,String> dismissPending(@PathVariable long id, @Valid @RequestBody Requests.Reason input) {
        fulfillment.resolvePending(id, false, input.reason());
        return ok();
    }

    /** FR-17: administrator registers a violation manually. */
    @PostMapping("/admin/violations")
    Map<String,String> registerViolation(@Valid @RequestBody Requests.Violation input) {
        fulfillment.manualViolation(input);
        return ok();
    }

    /** FR-16: run the fulfilment sweep on demand instead of waiting for the scheduler. */
    @PostMapping("/admin/sweep")
    Map<String,Integer> sweep() {
        return Map.of("processed", fulfillment.sweep());
    }

    private Map<String,String> ok() {
        return Map.of("message", "操作成功");
    }
}
