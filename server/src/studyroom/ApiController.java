package studyroom;

import jakarta.validation.Valid;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** REST 路由；需求编号只写在注释中，不出现在界面。
 *  FR-01~11 学生端；FR-12~25 管理端/统计；QR-SEC-04 审计。 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final BookingService bookings;
    private final ResourceService resources;
    private final FeedbackService feedback;

    public ApiController(BookingService bookings, ResourceService resources, FeedbackService feedback) {
        this.bookings = bookings;
        this.resources = resources;
        this.feedback = feedback;
    }

    // ---------- 学生端 ----------
    @GetMapping("/policy")
    Object policy() {
        return bookings.policy();
    }

    @GetMapping("/rooms")
    Object rooms() {
        return bookings.rooms(false);
    }

    @GetMapping("/rooms/{room}/seats")
    Object seats(@PathVariable long room,
                 @RequestParam(required=false) LocalDateTime start,
                 @RequestParam(required=false) LocalDateTime end) {
        return bookings.seats(room, start, end, false);
    }

    @GetMapping("/reservations")
    Object mine(@RequestParam(required=false) String status,
                @RequestParam(required=false) LocalDate date,
                @RequestParam(defaultValue="1") int page,
                @RequestParam(defaultValue="20") int size) {
        return bookings.reservations(false, status, date, page, size);
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    Object book(@Valid @RequestBody Requests.Booking input) {
        return bookings.create(input);
    }

    /** 只有取消、签到、签退三个学生动作；管理员代操作不在本期范围（报告 4.2 待确认）。 */
    @PostMapping("/reservations/{id}/{action}")
    Object transition(@PathVariable long id, @PathVariable String action) {
        bookings.transition(id, action);
        return ok();
    }

    @GetMapping("/violations")
    Object violations() {
        return bookings.violations(false);
    }

    @GetMapping("/complaints")
    Object complaints() {
        return feedback.complaints(false);
    }

    @PostMapping("/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    Object complain(@Valid @RequestBody Requests.Complaint input) {
        return Map.of("id", feedback.complain(input));
    }

    @PostMapping("/complaints/{id}/withdraw")
    Object withdraw(@PathVariable long id) {
        feedback.withdraw(id);
        return ok();
    }

    @GetMapping("/reviews")
    Object reviews() {
        return feedback.reviews(false);
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    Object review(@Valid @RequestBody Requests.Review input) {
        return Map.of("id", feedback.review(input));
    }

    // ---------- 管理员：资源 ----------
    @GetMapping("/admin/rooms")
    Object adminRooms() {
        return bookings.rooms(true);
    }

    @GetMapping("/admin/rooms/{room}/seats")
    Object adminSeats(@PathVariable long room,
                      @RequestParam(required=false) LocalDateTime start,
                      @RequestParam(required=false) LocalDateTime end) {
        return bookings.seats(room, start, end, true);
    }

    @PostMapping("/admin/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    Object addRoom(@Valid @RequestBody Requests.Room input) {
        return Map.of("id", resources.saveRoom(null, input));
    }

    @PutMapping("/admin/rooms/{id}")
    Object updateRoom(@PathVariable long id, @Valid @RequestBody Requests.Room input) {
        resources.saveRoom(id, input);
        return ok();
    }

    @DeleteMapping("/admin/rooms/{id}")
    Object deleteRoom(@PathVariable long id) {
        resources.delete("room", id);
        return ok();
    }

    @PostMapping("/admin/seats")
    @ResponseStatus(HttpStatus.CREATED)
    Object addSeat(@Valid @RequestBody Requests.Seat input) {
        return Map.of("id", resources.saveSeat(null, input));
    }

    @PutMapping("/admin/seats/{id}")
    Object updateSeat(@PathVariable long id, @Valid @RequestBody Requests.Seat input) {
        resources.saveSeat(id, input);
        return ok();
    }

    @DeleteMapping("/admin/seats/{id}")
    Object deleteSeat(@PathVariable long id) {
        resources.delete("seat", id);
        return ok();
    }

    // ---------- 管理员：预约与待核查 ----------
    @GetMapping("/admin/reservations")
    Object all(@RequestParam(required=false) String status,
               @RequestParam(required=false) LocalDate date,
               @RequestParam(defaultValue="1") int page,
               @RequestParam(defaultValue="20") int size) {
        return bookings.reservations(true, status, date, page, size);
    }

    @PostMapping("/admin/reservations/{id}/confirm-violation")
    Object confirmViolation(@PathVariable long id, @Valid @RequestBody Requests.Reason input) {
        bookings.resolvePending(id, true, input.reason());
        return ok();
    }

    @PostMapping("/admin/reservations/{id}/dismiss-pending")
    Object dismissPending(@PathVariable long id, @Valid @RequestBody Requests.Reason input) {
        bookings.resolvePending(id, false, input.reason());
        return ok();
    }

    // ---------- 管理员：违约 ----------
    @PostMapping("/admin/violations")
    Object violation(@Valid @RequestBody Requests.Violation input) {
        bookings.manualViolation(input);
        return ok();
    }

    @GetMapping("/admin/violations")
    Object allViolations() {
        return bookings.violations(true);
    }

    @PostMapping("/admin/violations/{id}/revoke")
    Object revoke(@PathVariable long id, @Valid @RequestBody Requests.Reason input) {
        bookings.revokeViolation(id, input.reason());
        return ok();
    }

    // ---------- 管理员：用户与黑名单 ----------
    @GetMapping("/admin/users")
    Object users(@RequestParam(required=false) String query) {
        return resources.users(query);
    }

    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    Object createUser(@Valid @RequestBody Requests.UserCreate input) {
        return Map.of("id", resources.createUser(input));
    }

    @PutMapping("/admin/users/{id}")
    Object user(@PathVariable long id, @Valid @RequestBody Requests.UserUpdate input) {
        resources.updateUser(id, input);
        return ok();
    }

    @PostMapping("/admin/users/{id}/blacklist")
    Object blacklist(@PathVariable long id, @Valid @RequestBody Requests.Blacklist input) {
        return Map.of("id", resources.blacklist(id, input));
    }

    @PostMapping("/admin/users/{id}/blacklist/release")
    Object releaseBlacklist(@PathVariable long id, @Valid @RequestBody Requests.Reason input) {
        resources.releaseBlacklist(id, input.reason());
        return ok();
    }

    @GetMapping("/admin/users/{id}/blacklist")
    Object blacklistRecords(@PathVariable long id) {
        return resources.blacklistRecords(id);
    }

    // ---------- 管理员：投诉、评价、统计、审计 ----------
    @GetMapping("/admin/complaints")
    Object allComplaints() {
        return feedback.complaints(true);
    }

    @PutMapping("/admin/complaints/{id}")
    Object reply(@PathVariable long id, @Valid @RequestBody Requests.Reply input) {
        feedback.reply(id, input);
        return ok();
    }

    @GetMapping("/admin/reviews")
    Object allReviews() {
        return feedback.reviews(true);
    }

    @PutMapping("/admin/reviews/{id}")
    Object moderate(@PathVariable long id, @RequestBody Requests.Visibility input) {
        feedback.visibility(id, input.visible());
        return ok();
    }

    @GetMapping("/admin/stats")
    Object stats() {
        return resources.stats();
    }

    @GetMapping("/admin/audits")
    Object audits() {
        return resources.audits();
    }

    @PostMapping("/admin/sweep")
    Object sweep() {
        return Map.of("processed", bookings.sweep());
    }

    private Map<String,String> ok() {
        return Map.of("message", "操作成功");
    }
}
