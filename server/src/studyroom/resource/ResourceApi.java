package studyroom.resource;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import studyroom.BookingService;
import studyroom.Requests;
import studyroom.ResourceService;

/**
 * Administrator resource endpoints.
 *
 * <p>Responsibilities: room and seat maintenance, user administration, blacklist
 * actions, statistics and audit queries. The room/seat state shown in the console
 * is read through {@link BookingService} so the "occupied / booked / free" rule has
 * exactly one implementation.
 */
@RestController
@RequestMapping("/api")
public class ResourceApi {
    private final ResourceService resources;
    private final BookingService bookings;

    public ResourceApi(ResourceService resources, BookingService bookings) {
        this.resources = resources;
        this.bookings = bookings;
    }

    /** FR-12: every room, including disabled ones, for the maintenance table. */
    @GetMapping("/admin/rooms")
    List<Map<String,Object>> rooms() {
        return bookings.rooms(true);
    }

    /** FR-13/FR-14: seats of a room with live state, including maintenance seats. */
    @GetMapping("/admin/rooms/{room}/seats")
    List<Map<String,Object>> seats(@PathVariable long room,
                                   @RequestParam(required=false) LocalDateTime start,
                                   @RequestParam(required=false) LocalDateTime end) {
        return bookings.seats(room, start, end, true);
    }

    /** FR-12: create a room. */
    @PostMapping("/admin/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Long> createRoom(@Valid @RequestBody Requests.Room input) {
        return Map.of("id", resources.saveRoom(null, input));
    }

    /** FR-12: update a room; blocked while it still has active reservations. */
    @PutMapping("/admin/rooms/{id}")
    Map<String,String> updateRoom(@PathVariable long id, @Valid @RequestBody Requests.Room input) {
        resources.saveRoom(id, input);
        return ok();
    }

    /** FR-12: delete a room only when it has no seats. */
    @DeleteMapping("/admin/rooms/{id}")
    Map<String,String> deleteRoom(@PathVariable long id) {
        resources.delete("room", id);
        return ok();
    }

    /** FR-13: create a seat under an existing room. */
    @PostMapping("/admin/seats")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Long> createSeat(@Valid @RequestBody Requests.Seat input) {
        return Map.of("id", resources.saveSeat(null, input));
    }

    /** FR-13: update a seat; a seat cannot move to another room. */
    @PutMapping("/admin/seats/{id}")
    Map<String,String> updateSeat(@PathVariable long id, @Valid @RequestBody Requests.Seat input) {
        resources.saveSeat(id, input);
        return ok();
    }

    /** FR-13: delete a seat only when it has no reservation history. */
    @DeleteMapping("/admin/seats/{id}")
    Map<String,String> deleteSeat(@PathVariable long id) {
        resources.delete("seat", id);
        return ok();
    }

    /** FR-18: search users by username or display name. */
    @GetMapping("/admin/users")
    List<Map<String,Object>> users(@RequestParam(required=false) String query) {
        return resources.users(query);
    }

    /** FR-18: create a student account. */
    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Long> createUser(@Valid @RequestBody Requests.UserCreate input) {
        return Map.of("id", resources.createUser(input));
    }

    /** FR-18: rename or enable/disable an account. */
    @PutMapping("/admin/users/{id}")
    Map<String,String> updateUser(@PathVariable long id, @Valid @RequestBody Requests.UserUpdate input) {
        resources.updateUser(id, input);
        return ok();
    }

    /** FR-19: add a blacklist record with its own reason and end date. */
    @PostMapping("/admin/users/{id}/blacklist")
    Map<String,Long> addBlacklist(@PathVariable long id, @Valid @RequestBody Requests.Blacklist input) {
        return Map.of("id", resources.blacklist(id, input));
    }

    /** FR-19: release every active blacklist record of one student. */
    @PostMapping("/admin/users/{id}/blacklist/release")
    Map<String,String> releaseBlacklist(@PathVariable long id, @Valid @RequestBody Requests.Reason input) {
        resources.releaseBlacklist(id, input.reason());
        return ok();
    }

    /** FR-19: blacklist history of one student. */
    @GetMapping("/admin/users/{id}/blacklist")
    List<Map<String,Object>> blacklistHistory(@PathVariable long id) {
        return resources.blacklistRecords(id);
    }

    /** FR-23/24/25: counters and charts for the administrator dashboard. */
    @GetMapping("/admin/stats")
    Map<String,Object> statistics() {
        return resources.stats();
    }

    /** QR-SEC-04: the latest audit rows. */
    @GetMapping("/admin/audits")
    List<Map<String,Object>> auditLogs() {
        return resources.audits();
    }

    private Map<String,String> ok() {
        return Map.of("message", "操作成功");
    }
}
