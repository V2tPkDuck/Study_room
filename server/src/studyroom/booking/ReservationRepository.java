package studyroom.booking;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import studyroom.platform.Store;

/**
 * Sole writer of {@code reservations}; owns the view SQL and the paged query.
 */
@Repository
public class ReservationRepository {
    /**
     * Only BOOKED and CHECKED_IN hold a seat slot; PENDING has already released it.
     */
    static final String ACTIVE = "('BOOKED','CHECKED_IN')";

    static final String VIEW = "SELECT b.*,u.username,u.display_name,s.code AS seat_code,"
            + "r.name AS room_name,r.id AS room_id FROM reservations b "
            + "JOIN app_users u ON u.id=b.user_id JOIN seats s ON s.id=b.seat_id JOIN rooms r ON r.id=s.room_id";

    private final Store db;

    public ReservationRepository(Store db) {
        this.db = db;
    }

    Map<String, Object> byId(long id) {
        return db.one("SELECT * FROM reservations WHERE id=?", id);
    }

    Map<String, Object> view(long id) {
        return db.one(VIEW + " WHERE b.id=?", id);
    }

    long countActiveOfUser(long userId) {
        return db.count("SELECT COUNT(*) FROM reservations WHERE user_id=? AND status IN " + ACTIVE, userId);
    }

    long countSeatConflicts(long seatId, LocalDateTime start, LocalDateTime end) {
        return db.count("SELECT COUNT(*) FROM reservations WHERE seat_id=? AND status IN " + ACTIVE
                + " AND start_at<? AND end_at>?", seatId, end, start);
    }

    long countUserConflicts(long userId, LocalDateTime start, LocalDateTime end) {
        return db.count("SELECT COUNT(*) FROM reservations WHERE user_id=? AND status IN " + ACTIVE
                + " AND start_at<? AND end_at>?", userId, end, start);
    }

    long insert(long userId, long seatId, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        return db.insert("INSERT INTO reservations(user_id,seat_id,start_at,end_at,status,created_at) "
                + "VALUES(?,?,?,?,'BOOKED',?)", userId, seatId, start, end, now);
    }

    Map<String, Object> page(boolean admin, long userId, String status, LocalDate date, int page, int size) {
        String where = " WHERE 1=1";
        List<Object> args = new ArrayList<>();
        if (!admin) {
            where += " AND b.user_id=?";
            args.add(userId);
        }
        if (status != null && !status.isBlank()) {
            where += " AND b.status=?";
            args.add(status);
        }
        if (date != null) {
            where += " AND b.start_at>=? AND b.start_at<?";
            args.add(date.atStartOfDay());
            args.add(date.plusDays(1).atStartOfDay());
        }
        long total = db.count("SELECT COUNT(*) FROM reservations b" + where, args.toArray());
        args.add(size);
        args.add((long) (page - 1) * size);
        return Map.of(
                "items", db.list(VIEW + where + " ORDER BY b.id DESC LIMIT ? OFFSET ?", args.toArray()),
                "total", total, "page", page, "size", size);
    }

    void cancel(long id, String remark) {
        db.update("UPDATE reservations SET status='CANCELLED',remark=? WHERE id=?", remark, id);
    }

    void checkIn(long id, LocalDateTime now) {
        db.update("UPDATE reservations SET status='CHECKED_IN',check_in_at=? WHERE id=?", now, id);
    }

    void checkOut(long id, LocalDateTime at) {
        db.update("UPDATE reservations SET status='COMPLETED',check_out_at=? WHERE id=?", at, id);
    }

    List<Map<String, Object>> noShowCandidates(LocalDateTime threshold, LocalDateTime now) {
        return db.list("SELECT * FROM reservations WHERE status='BOOKED' AND (start_at<=? OR end_at<=?)",
                threshold, now);
    }

    void toPending(long id) {
        db.update("UPDATE reservations SET status='PENDING',remark=? WHERE id=?",
                "超过签到宽限，等待管理员核查", id);
    }

    List<Map<String, Object>> expiredCheckIns(LocalDateTime now) {
        return db.list("SELECT * FROM reservations WHERE status='CHECKED_IN' AND end_at<=?", now);
    }

    void autoComplete(long id) {
        db.update("UPDATE reservations SET status='COMPLETED',check_out_at=end_at WHERE id=?", id);
    }

    void toViolated(long id, String reason) {
        db.update("UPDATE reservations SET status='VIOLATED',remark=? WHERE id=?", reason, id);
    }

    void toCancelled(long id, String reason) {
        db.update("UPDATE reservations SET status='CANCELLED',remark=? WHERE id=?", reason, id);
    }

    void toViolatedManually(long id, LocalDateTime now, String reason) {
        db.update("UPDATE reservations SET status='VIOLATED',check_out_at=?,remark=? WHERE id=?", now, reason, id);
    }
}