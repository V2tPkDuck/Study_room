package studyroom;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studyroom.platform.BusinessException;
import studyroom.platform.IdentityService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;
import static studyroom.platform.Store.*;

/**
 * Reservation lifecycle: create, list, cancel, check-in and check-out.
 *
 * <p>Requirement map: FR-01/02 room and seat lookup, FR-03 booking, FR-04 cancel,
 * FR-05/06 check-in and check-out, FR-07/08 personal history, FR-14 live seats,
 * FR-15 usage records, FR-16/17 pending review and violations, FR-19 blacklist.
 *
 * <p>State transitions follow SRS 5.4, plus the PENDING buffer from report V-01:
 * a missed check-in becomes PENDING for an administrator, never an automatic violation.
 */
@Service
public class BookingService {
    private final Store db;
    private final IdentityService identity;
    private final Clock clock;

    /** Only BOOKED and CHECKED_IN hold a seat slot; PENDING has already released it. */
    private static final String ACTIVE = "('BOOKED','CHECKED_IN')";

    static final String VIEW = "SELECT b.*,u.username,u.display_name,s.code AS seat_code,"
        + "r.name AS room_name,r.id AS room_id FROM reservations b "
        + "JOIN app_users u ON u.id=b.user_id JOIN seats s ON s.id=b.seat_id JOIN rooms r ON r.id=s.room_id";

    private static final Set<String> STATUSES = Set.of(
        "BOOKED", "PENDING", "CHECKED_IN", "COMPLETED", "CANCELLED", "VIOLATED");

    public BookingService(Store db, IdentityService identity, Clock clock) {
        this.db = db;
        this.identity = identity;
        this.clock = clock;
    }

    public Map<String,Object> policy() {
        identity.current();
        return rawPolicy();
    }

    private Map<String,Object> rawPolicy() {
        return db.one("SELECT * FROM reservation_policy WHERE id=1");
    }

    public List<Map<String,Object>> rooms(boolean admin) {
        if (admin) identity.admin(); else identity.current();
        return db.list("SELECT r.*,(SELECT COUNT(*) FROM seats s WHERE s.room_id=r.id) AS seat_count "
            + "FROM rooms r " + (admin ? "" : "WHERE r.enabled=TRUE ") + "ORDER BY r.id");
    }

    public List<Map<String,Object>> seats(long roomId, LocalDateTime start, LocalDateTime end, boolean admin) {
        if (admin) identity.admin(); else identity.current();
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
            + "(SELECT COUNT(*) FROM reservations b WHERE b.seat_id=s.id AND b.status IN " + ACTIVE
            + " AND b.start_at<? AND b.end_at>?) AS conflicts,"
            + "(SELECT COUNT(*) FROM reservations b WHERE b.seat_id=s.id AND b.status='CHECKED_IN'"
            + " AND b.check_in_at<=? AND b.end_at>?) AS occupied "
            + "FROM seats s JOIN rooms r ON r.id=s.room_id WHERE s.room_id=? ORDER BY s.code",
            end, start, now, now, roomId);

        boolean rangeIncludesNow = !start.isAfter(now) && end.isAfter(now);
        for (var row : rows) {
            boolean enabled = flag(row, "enabled") && flag(room, "enabled");
            long conflicts = ((Number)row.get("conflicts")).longValue();
            long occupied = ((Number)row.get("occupied")).longValue();
            String state = !enabled ? "维护中"
                : (occupied > 0 && rangeIncludesNow) ? "使用中"
                : conflicts > 0 ? "已预约" : "空闲";
            row.put("state", state);
            row.put("bookable", enabled && conflicts == 0);
    /** Quiet zone is a room attribute used by the seat filter; the seat row does not store it. */
            row.put("is_quiet", flag(row, "room_quiet"));
        }
        return rows;
    }

    @Transactional
    public Map<String,Object> create(Requests.Booking input) {
        db.lock();
        var user = identity.current();
        var now = LocalDateTime.now(clock);
        var policy = rawPolicy();

        var black = time(user, "blacklist_until");
        require(black == null || !black.isAfter(now), 403, "账号处于黑名单限制期，截止 " + black);

        var seat = db.one("SELECT s.*,r.enabled AS room_enabled,r.open_time,r.close_time "
            + "FROM seats s JOIN rooms r ON r.id=s.room_id WHERE s.id=?", input.seatId());
        require(flag(seat, "enabled") && flag(seat, "room_enabled"), 409, "座位或自习室已停用");

        var start = input.startAt();
        var end = input.endAt();
        require(start.isBefore(end), 400, "结束时间应晚于开始时间");
        require(!start.isBefore(now), 400, "不能预约过去的时间");
        require(start.toLocalDate().equals(end.toLocalDate()), 400, "预约不能跨天");
        require(!start.toLocalDate().isAfter(now.toLocalDate().plusDays(id(policy, "max_days"))),
            400, "超出可预约日期范围");
        long seconds = Duration.between(start, end).getSeconds();
        require(seconds >= 900 && seconds <= id(policy, "max_hours") * 3600,
            400, "预约时长至少 15 分钟，且不能超过最大时长");
        require(!start.toLocalTime().isBefore(LocalTime.parse((String)seat.get("open_time")))
                && !end.toLocalTime().isAfter(LocalTime.parse((String)seat.get("close_time"))),
            400, "预约时间不在自习室开放时间内");

        long active = db.count("SELECT COUNT(*) FROM reservations WHERE user_id=? AND status IN " + ACTIVE,
            user.get("id"));
        require(active < id(policy, "max_active"),
            409, "每人最多同时保留 " + id(policy, "max_active") + " 条待使用预约");

        require(db.count("SELECT COUNT(*) FROM reservations WHERE seat_id=? AND status IN " + ACTIVE
                + " AND start_at<? AND end_at>?", input.seatId(), end, start) == 0,
            409, "该座位在所选时段已被预约");
        require(db.count("SELECT COUNT(*) FROM reservations WHERE user_id=? AND status IN " + ACTIVE
                + " AND start_at<? AND end_at>?", user.get("id"), end, start) == 0,
            409, "你已有重叠时段的预约");

        long booking = db.insert("INSERT INTO reservations(user_id,seat_id,start_at,end_at,status,created_at) "
            + "VALUES(?,?,?,?,'BOOKED',?)", user.get("id"), input.seatId(), start, end, now);
        identity.audit((String)user.get("username"), "BOOK", "RESERVATION", booking,
            "创建预约：" + start + " ~ " + end);
        return db.one(VIEW + " WHERE b.id=?", booking);
    }

    public Map<String,Object> reservations(boolean admin, String status, LocalDate date, int page, int size) {
        var user = admin ? identity.admin() : identity.current();
        require(page >= 1 && size >= 1 && size <= 100, 400, "页码至少 1，每页 1-100 条");
        String where = " WHERE 1=1";
        List<Object> args = new ArrayList<>();
        if (!admin) {
            where += " AND b.user_id=?";
            args.add(user.get("id"));
        }
        if (status != null && !status.isBlank()) {
            require(STATUSES.contains(status), 400, "预约状态无效");
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
        args.add((long)(page - 1) * size);
        return Map.of(
            "items", db.list(VIEW + where + " ORDER BY b.id DESC LIMIT ? OFFSET ?", args.toArray()),
            "total", total, "page", page, "size", size);
    }

    /** Student actions only: cancel, check-in, check-out. Admin proxy actions are out of scope (SRS 4.2). */
    @Transactional
    public void transition(long booking, String action) {
        db.lock();
        var user = identity.current();
        var b = db.one("SELECT * FROM reservations WHERE id=?", booking);
        require(id(user, "id") == id(b, "user_id"), 403, "不能操作他人的预约");

        var now = LocalDateTime.now(clock);
        var start = time(b, "start_at");
        var end = time(b, "end_at");
        var policy = rawPolicy();
        String status = (String)b.get("status");

        switch (action) {
            case "cancel" -> {
                require("BOOKED".equals(status), 409, "仅“已预约”的预约可以取消");
                require(now.isBefore(start), 409, "预约已开始，不能取消");
                db.update("UPDATE reservations SET status='CANCELLED',remark=? WHERE id=?", "学生取消", booking);
            }
            case "check-in" -> {
                require("BOOKED".equals(status), 409, "当前状态不能签到");
                require(!now.isBefore(start.minusMinutes(id(policy, "check_in_before")))
                        && now.isBefore(start.plusMinutes(id(policy, "grace_minutes")))
                        && now.isBefore(end),
                    409, "当前不在签到时间窗口内");
                db.update("UPDATE reservations SET status='CHECKED_IN',check_in_at=? WHERE id=?", now, booking);
            }
            case "check-out" -> {
                require("CHECKED_IN".equals(status), 409, "只有“已签到”的预约可以签退");
                db.update("UPDATE reservations SET status='COMPLETED',check_out_at=? WHERE id=?",
                    now.isBefore(end) ? now : end, booking);
            }
            default -> throw new BusinessException(400, "未知操作");
        }
        identity.audit((String)user.get("username"), action.toUpperCase(Locale.ROOT),
            "RESERVATION", booking, "状态从 " + status + " 流转");
    }

    /**
     * Auto-fulfilment: a missed check-in becomes PENDING for manual review, never a
     * direct violation (report V-01). An expired checked-in reservation is completed.
     * The task is idempotent: running it twice processes nothing twice.
     */
    @Transactional
    public int sweep() {
        db.lock();
        var now = LocalDateTime.now(clock);
        var policy = rawPolicy();
        int count = 0;

        for (var b : db.list("SELECT * FROM reservations WHERE status='BOOKED' AND (start_at<=? OR end_at<=?)",
                now.minusMinutes(id(policy, "grace_minutes")), now)) {
            db.update("UPDATE reservations SET status='PENDING',remark=? WHERE id=?",
                "超过签到宽限，等待管理员核查", b.get("id"));
            identity.audit("SYSTEM", "PENDING_NO_CHECK_IN", "RESERVATION", id(b, "id"),
                "超时未签到，转待核查");
            count++;
        }
        for (var b : db.list("SELECT * FROM reservations WHERE status='CHECKED_IN' AND end_at<=?", now)) {
            db.update("UPDATE reservations SET status='COMPLETED',check_out_at=end_at WHERE id=?", b.get("id"));
            identity.audit("SYSTEM", "AUTO_CHECK_OUT", "RESERVATION", id(b, "id"),
                "预约到期自动签退");
            count++;
        }
        return count;
    }

    /** Administrator closes a PENDING reservation: confirm the violation, or dismiss and release it. */
    @Transactional
    public void resolvePending(long booking, boolean violation, String reason) {
        db.lock();
        var admin = identity.admin();
        var b = db.one("SELECT * FROM reservations WHERE id=?", booking);
        require("PENDING".equals(b.get("status")), 409, "该预约不在待核查状态");

        if (violation) {
            db.update("UPDATE reservations SET status='VIOLATED',remark=? WHERE id=?", reason, booking);
            recordViolation(b, "AUTO", reason, (String)admin.get("username"));
            identity.audit((String)admin.get("username"), "CONFIRM_NO_CHECK_IN", "RESERVATION", booking, reason);
        } else {
            db.update("UPDATE reservations SET status='CANCELLED',remark=? WHERE id=?", reason, booking);
            identity.audit((String)admin.get("username"), "DISMISS_PENDING", "RESERVATION", booking, reason);
        }
    }

    /** Administrator records a violation manually; one reservation can own at most one violation. */
    @Transactional
    public void manualViolation(Requests.Violation input) {
        db.lock();
        var admin = identity.admin();
        var b = db.one("SELECT * FROM reservations WHERE id=?", input.reservationId());
        require(Set.of("BOOKED", "CHECKED_IN", "PENDING").contains(b.get("status")),
            409, "只有未结束的预约可以登记违约");
        require(db.count("SELECT COUNT(*) FROM violations WHERE reservation_id=?", input.reservationId()) == 0,
            409, "该预约已有违约记录");

        db.update("UPDATE reservations SET status='VIOLATED',check_out_at=?,remark=? WHERE id=?",
            LocalDateTime.now(clock), input.reason(), input.reservationId());
        recordViolation(b, "MANUAL", input.reason(), (String)admin.get("username"));
        identity.audit((String)admin.get("username"), "MANUAL_VIOLATION", "RESERVATION",
            input.reservationId(), input.reason());
    }

    /**
     * Adds one violation. When the active count reaches the policy threshold the
     * student is blacklisted automatically. An existing active blacklist is reused,
     * so repeated sweeps or retries do not extend the end date.
     */
    private void recordViolation(Map<String,Object> b, String source, String reason, String actor) {
        var now = LocalDateTime.now(clock);
        long violation = db.insert("INSERT INTO violations(user_id,reservation_id,source,reason,revoked,created_by,created_at) "
            + "VALUES(?,?,?,?,FALSE,?,?)", b.get("user_id"), b.get("id"), source, reason, actor, now);
        identity.audit(actor, "VIOLATION", "VIOLATION", violation, reason);

        var policy = rawPolicy();
        long count = db.count("SELECT COUNT(*) FROM violations WHERE user_id=? AND revoked=FALSE", b.get("user_id"));
        if (count >= id(policy, "blacklist_threshold")) {
            applyBlacklist(b.get("user_id"), "有效违约达到 " + count + " 次",
                (int)id(policy, "blacklist_days"), "SYSTEM");
        }
    }

    /** Automatic blacklist: reuse an active record; administrator-issued ones go through ResourceService. */
    private void applyBlacklist(Object userId, String reason, int days, String actor) {
        var now = LocalDateTime.now(clock);
        long active = db.count("SELECT COUNT(*) FROM blacklist_records WHERE user_id=? AND active=TRUE AND end_at>?",
            userId, now);
        if (active > 0) return;

        var end = now.plusDays(days);
        long record = db.insert("INSERT INTO blacklist_records(user_id,reason,start_at,end_at,created_by,active) "
            + "VALUES(?,?,?,?,?,TRUE)", userId, reason, now, end, actor);
        var user = db.one("SELECT blacklist_until FROM app_users WHERE id=?", userId);
        var current = time(user, "blacklist_until");
        if (current == null || current.isBefore(end)) {
            db.update("UPDATE app_users SET blacklist_until=? WHERE id=?", end, userId);
        }
        identity.audit(actor, "BLACKLIST", "BLACKLIST", record, reason);
    }

    @Transactional
    public void revokeViolation(long violation, String reason) {
        db.lock();
        var admin = identity.admin();
        var v = db.one("SELECT * FROM violations WHERE id=?", violation);
        require(!flag(v, "revoked"), 409, "记录已撤销");
        db.update("UPDATE violations SET revoked=TRUE,revoke_reason=? WHERE id=?", reason, violation);
        identity.audit((String)admin.get("username"), "REVOKE_VIOLATION", "VIOLATION", violation, reason);
    }

    public List<Map<String,Object>> violations(boolean admin) {
        var user = admin ? identity.admin() : identity.current();
        return db.list("SELECT v.*,u.username,u.display_name FROM violations v "
            + "JOIN app_users u ON v.user_id=u.id "
            + (admin ? "" : "WHERE v.user_id=? ")
            + "ORDER BY v.id DESC LIMIT 500", admin ? new Object[]{} : new Object[]{user.get("id")});
    }
}
