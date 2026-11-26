package studyroom.resource;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import studyroom.booking.SeatQueryService;
/**
 * Administrator resource endpoints.
 *
 * <p>Responsibilities: room and seat maintenance, user administration, blacklist
 * actions, statistics and audit queries. The room/seat state shown in the console
 * is read through {@link SeatQueryService} so the "occupied / booked / free" rule has
 * exactly one implementation.
 */
@RestController
@RequestMapping("/api")
public class ResourceApi {
    private final RoomService rooms;
    private final SeatAdminService seats;
    private final UserAdminService userAdmin;
    private final StatsService stats;
    private final SeatQueryService seatQuery;

    public ResourceApi(RoomService rooms, SeatAdminService seats, UserAdminService userAdmin,
            StatsService stats, SeatQueryService seatQuery) {
        this.rooms = rooms;
        this.seats = seats;
        this.userAdmin = userAdmin;
        this.stats = stats;
        this.seatQuery = seatQuery;
    }

    /** FR-12: every room, including disabled ones, for the maintenance table. */
    @GetMapping("/admin/rooms")
    List<Map<String,Object>> rooms() {
        return seatQuery.rooms(true);
    }

    /** FR-13/FR-14: seats of a room with live state, including maintenance seats. */
    @GetMapping("/admin/rooms/{room}/seats")
    List<Map<String,Object>> seats(@PathVariable long room,
                                   @RequestParam(required=false) LocalDateTime start,
                                   @RequestParam(required=false) LocalDateTime end) {
        return seatQuery.seats(room, start, end, true);
    }

    /** FR-12: create a room. */
    @PostMapping("/admin/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Long> createRoom(@Valid @RequestBody ResourceRequests.Room input) {
        return Map.of("id", rooms.saveRoom(null, input));
    }

    /** FR-12: update a room; blocked while it still has active reservations. */
    @PutMapping("/admin/rooms/{id}")
    Map<String,String> updateRoom(@PathVariable long id, @Valid @RequestBody ResourceRequests.Room input) {
        rooms.saveRoom(id, input);
        return ok();
    }

    /** FR-12: delete a room only when it has no seats. */
    @DeleteMapping("/admin/rooms/{id}")
    Map<String,String> deleteRoom(@PathVariable long id) {
        rooms.delete(id);
        return ok();
    }

    /** FR-13: create a seat under an existing room. */
    @PostMapping("/admin/seats")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Long> createSeat(@Valid @RequestBody ResourceRequests.Seat input) {
        return Map.of("id", seats.saveSeat(null, input));
    }

    /** FR-13: update a seat; a seat cannot move to another room. */
    @PutMapping("/admin/seats/{id}")
    Map<String,String> updateSeat(@PathVariable long id, @Valid @RequestBody ResourceRequests.Seat input) {
        seats.saveSeat(id, input);
        return ok();
    }

    /** FR-13: delete a seat only when it has no reservation history. */
    @DeleteMapping("/admin/seats/{id}")
    Map<String,String> deleteSeat(@PathVariable long id) {
        seats.delete(id);
        return ok();
    }

    /** FR-18: search users by username or display name. */
    @GetMapping("/admin/users")
    List<Map<String,Object>> users(@RequestParam(required=false) String query) {
        return userAdmin.users(query);
    }

    /** FR-18: create a student account. */
    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Long> createUser(@Valid @RequestBody ResourceRequests.UserCreate input) {
        return Map.of("id", userAdmin.createUser(input));
    }

    /** FR-18: rename or enable/disable an account. */
    @PutMapping("/admin/users/{id}")
    Map<String,String> updateUser(@PathVariable long id, @Valid @RequestBody ResourceRequests.UserUpdate input) {
        userAdmin.updateUser(id, input);
        return ok();
    }
    
    /** FR-23/24/25: counters and charts for the administrator dashboard. */
    @GetMapping("/admin/stats")
    Map<String,Object> statistics() {
        return stats.stats();
    }

    private Map<String,String> ok() {
        return Map.of("message", "操作成功");
    }
}
