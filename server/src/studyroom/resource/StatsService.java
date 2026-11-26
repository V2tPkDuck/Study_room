
package studyroom.resource;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import studyroom.platform.IdentityService;
import studyroom.platform.Store;

/**
 * FR-23/24/25: read-only statistics projection; allowed to aggregate across
 * slices (ADR/Q-07).
 */
@Service
public class StatsService {
    private final Store db;
    private final IdentityService identity;
    private final Clock clock;

    public StatsService(Store db, IdentityService identity, Clock clock) {
        this.db = db;
        this.identity = identity;
        this.clock = clock;
    }

    public Map<String, Object> stats() {
        identity.admin();
        var now = LocalDateTime.now(clock);
        var today = now.toLocalDate();

        List<Map<String, Object>> bars = db.list("SELECT r.name,COUNT(b.id) AS bookings FROM rooms r "
                + "LEFT JOIN seats s ON s.room_id=r.id "
                + "LEFT JOIN reservations b ON b.seat_id=s.id AND b.start_at>=? AND b.start_at<? "
                + "GROUP BY r.id,r.name ORDER BY r.id", today.atStartOfDay(), today.plusDays(1).atStartOfDay());

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            var day = today.minusDays(i);
            trend.add(Map.of(
                    "date", day.toString(),
                    "bookings", db.count("SELECT COUNT(*) FROM reservations WHERE start_at>=? AND start_at<?",
                            day.atStartOfDay(), day.plusDays(1).atStartOfDay()),
                    "violations", db.count("SELECT COUNT(*) FROM violations WHERE revoked=FALSE "
                            + "AND created_at>=? AND created_at<?", day.atStartOfDay(),
                            day.plusDays(1).atStartOfDay())));
        }

        return Map.of(
                "updated_at", now,
                "users", db.count("SELECT COUNT(*) FROM app_users WHERE role='STUDENT'"),
                "seats", db.count("SELECT COUNT(*) FROM seats s JOIN rooms r ON s.room_id=r.id "
                        + "WHERE s.enabled=TRUE AND r.enabled=TRUE"),
                "occupied", db.count("SELECT COUNT(*) FROM reservations WHERE status='CHECKED_IN' "
                        + "AND check_in_at<=? AND end_at>?", now, now),
                "today_bookings", db.count("SELECT COUNT(*) FROM reservations WHERE start_at>=? AND start_at<?",
                        today.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                "open_complaints", db.count("SELECT COUNT(*) FROM complaints WHERE status IN ('PENDING','PROCESSING')"),
                "rooms", bars,
                "trend", trend);
    }
}