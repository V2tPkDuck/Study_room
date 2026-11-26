package studyroom.booking;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import studyroom.platform.IdentityService;
import studyroom.platform.PolicyService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;
import static studyroom.platform.Store.flag;

/**
 * Room/seat queries; the live seat state has exactly one implementation
 * (FR-01/02/14).
 */
@Service
public class SeatQueryService {
    private final Store db;
    private final IdentityService identity;
    private final PolicyService policyService;
    private final Clock clock;

    public SeatQueryService(Store db, IdentityService identity, PolicyService policyService, Clock clock) {
        this.db = db;
        this.identity = identity;
        this.policyService = policyService;
        this.clock = clock;
    }

    public Map<String, Object> policy() {
        identity.current();
        return policyService.current();
    }

    public List<Map<String, Object>> rooms(boolean admin) {
        if (admin)
            identity.admin();
        else
            identity.current();
        return db.list("SELECT r.*,(SELECT COUNT(*) FROM seats s WHERE s.room_id=r.id) AS seat_count "
                + "FROM rooms r " + (admin ? "" : "WHERE r.enabled=TRUE ") + "ORDER BY r.id");
    }

    public List<Map<String, Object>> seats(long roomId, LocalDateTime start, LocalDateTime end, boolean admin) {
        if (admin)
            identity.admin();
        else
            identity.current();
        var room = db.one("SELECT * FROM rooms WHERE id=?", roomId);
        require(admin || flag(room, "enabled"), 404, "自习室未开放");
        require((start == null) == (end == null), 400, "开始和结束时间必须同时填写");
        var now = LocalDateTime.now(clock);
        if (start == null) {
            start = now;
            end = now.plusMinutes(1);
        }
        require(start.isBefore(end), 400, "结束时间应晚于开始时间");

        var rows = db.list("SELECT s.*,r.is_quiet AS room_quiet,"
                + "(SELECT COUNT(*) FROM reservations b WHERE b.seat_id=s.id AND b.status IN "
                + ReservationRepository.ACTIVE
                + " AND b.start_at<? AND b.end_at>?) AS conflicts,"
                + "(SELECT COUNT(*) FROM reservations b WHERE b.seat_id=s.id AND b.status='CHECKED_IN'"
                + " AND b.check_in_at<=? AND b.end_at>?) AS occupied "
                + "FROM seats s JOIN rooms r ON r.id=s.room_id WHERE s.room_id=? ORDER BY s.code",
                end, start, now, now, roomId);

        boolean rangeIncludesNow = !start.isAfter(now) && end.isAfter(now);
        for (var row : rows) {
            boolean enabled = flag(row, "enabled") && flag(room, "enabled");
            long conflicts = ((Number) row.get("conflicts")).longValue();
            long occupied = ((Number) row.get("occupied")).longValue();
            String state = !enabled ? "维护中"
                    : (occupied > 0 && rangeIncludesNow) ? "使用中"
                            : conflicts > 0 ? "已预约" : "空闲";
            row.put("state", state);
            row.put("bookable", enabled && conflicts == 0);
            row.put("is_quiet", flag(row, "room_quiet"));
        }
        return rows;
    }

    /** Seat + room hours/state used by booking creation. */
    Map<String, Object> seatContext(long seatId) {
        return db.one("SELECT s.*,r.enabled AS room_enabled,r.open_time,r.close_time "
                + "FROM seats s JOIN rooms r ON r.id=s.room_id WHERE s.id=?", seatId);
    }
}