package studyroom;

import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studyroom.platform.IdentityService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;
import static studyroom.platform.Store.*;

/** Rooms, seats, users, blacklists, statistics and audit queries.
 *  Requirement map: FR-12/13 resources, FR-18 users, FR-19 blacklist, FR-23/24 statistics, QR-SEC-04 audit. */
@Service
public class ResourceService {
    private final Store db;
    private final IdentityService identity;
    private final Clock clock;
    private final PasswordEncoder passwords;

    public ResourceService(Store db, IdentityService identity, Clock clock, PasswordEncoder passwords) {
        this.db = db;
        this.identity = identity;
        this.clock = clock;
        this.passwords = passwords;
    }

    @Transactional
    public long saveRoom(Long roomId, Requests.Room input) {
        db.lock();
        var admin = identity.admin();
        require(input.openTime() != null && input.closeTime() != null, 400, "开放时间不能为空");
        require(LocalTime.parse(input.openTime()).isBefore(LocalTime.parse(input.closeTime())),
            400, "开放时间需早于关闭时间，不支持跨夜开放");

        if (roomId == null) {
            roomId = db.insert("INSERT INTO rooms(name,building,floor_no,open_time,close_time,description,enabled,is_quiet) "
                + "VALUES(?,?,?,?,?,?,?,?)", input.name().trim(), input.building().trim(), input.floorNo(),
                input.openTime(), input.closeTime(), input.description(), input.enabled(), input.isQuiet());
        } else {
            db.one("SELECT id FROM rooms WHERE id=?", roomId);
            require(db.count("SELECT COUNT(*) FROM reservations b JOIN seats s ON b.seat_id=s.id "
                    + "WHERE s.room_id=? AND b.status IN ('BOOKED','CHECKED_IN')", roomId) == 0,
                409, "该自习室仍有有效预约，请先处理预约再修改");
            db.update("UPDATE rooms SET name=?,building=?,floor_no=?,open_time=?,close_time=?,description=?,enabled=?,is_quiet=? "
                + "WHERE id=?", input.name().trim(), input.building().trim(), input.floorNo(),
                input.openTime(), input.closeTime(), input.description(), input.enabled(), input.isQuiet(), roomId);
        }
        identity.audit((String)admin.get("username"), "SAVE_ROOM", "ROOM", roomId, input.name());
        return roomId;
    }

    @Transactional
    public long saveSeat(Long seatId, Requests.Seat input) {
        db.lock();
        var admin = identity.admin();
        db.one("SELECT id FROM rooms WHERE id=?", input.roomId());

        if (seatId == null) {
            seatId = db.insert("INSERT INTO seats(room_id,code,has_power,enabled) VALUES(?,?,?,?)",
                input.roomId(), input.code().trim(), input.hasPower(), input.enabled());
        } else {
            var old = db.one("SELECT * FROM seats WHERE id=?", seatId);
            require(id(old, "room_id") == input.roomId(), 400, "已有座位不能转移到其他自习室");
            require(db.count("SELECT COUNT(*) FROM reservations WHERE seat_id=? AND status IN ('BOOKED','CHECKED_IN')",
                seatId) == 0, 409, "座位仍有有效预约，请先处理预约");
            db.update("UPDATE seats SET code=?,has_power=?,enabled=? WHERE id=?",
                input.code().trim(), input.hasPower(), input.enabled(), seatId);
        }
        identity.audit((String)admin.get("username"), "SAVE_SEAT", "SEAT", seatId, input.code());
        return seatId;
    }

    @Transactional
    public void delete(String type, long resource) {
        db.lock();
        var admin = identity.admin();
        if (type.equals("room")) {
            db.one("SELECT id FROM rooms WHERE id=?", resource);
            require(db.count("SELECT COUNT(*) FROM seats WHERE room_id=?", resource) == 0,
                409, "请先删除该自习室的座位；有历史记录的资源请停用");
            db.update("DELETE FROM rooms WHERE id=?", resource);
        } else {
            db.one("SELECT id FROM seats WHERE id=?", resource);
            require(db.count("SELECT COUNT(*) FROM reservations WHERE seat_id=?", resource) == 0,
                409, "座位已有预约历史，请使用停用功能");
            db.update("DELETE FROM seats WHERE id=?", resource);
        }
        identity.audit((String)admin.get("username"), "DELETE_RESOURCE",
            type.toUpperCase(Locale.ROOT), resource, "删除无历史记录的资源");
    }

    public List<Map<String,Object>> users(String query) {
        identity.admin();
        String q = query == null ? "" : query;
        return db.list("SELECT " + IdentityService.PUBLIC_FIELDS + " FROM app_users "
            + "WHERE username LIKE ? OR display_name LIKE ? ORDER BY id DESC LIMIT 500",
            "%" + q + "%", "%" + q + "%");
    }

    /** FR-18: administrator creates a student account; self-registration is out of scope. */
    @Transactional
    public long createUser(Requests.UserCreate input) {
        db.lock();
        var admin = identity.admin();
        require(db.count("SELECT COUNT(*) FROM app_users WHERE username=?", input.username()) == 0,
            409, "账号已存在");
        var now = LocalDateTime.now(clock);
        long userId = db.insert("INSERT INTO app_users(username,display_name,password_hash,role,enabled,created_at) "
            + "VALUES(?,?,?,'STUDENT',TRUE,?)", input.username(), input.displayName().trim(),
            passwords.encode(input.password()), now);
        identity.audit((String)admin.get("username"), "CREATE_USER", "USER", userId, input.username());
        return userId;
    }

    @Transactional
    public void updateUser(long userId, Requests.UserUpdate input) {
        db.lock();
        var admin = identity.admin();
        db.one("SELECT id FROM app_users WHERE id=?", userId);
        require(id(admin, "id") != userId || input.enabled(), 400, "不能停用当前登录账号");
        db.update("UPDATE app_users SET display_name=?,enabled=? WHERE id=?",
            input.displayName().trim(), input.enabled(), userId);
        identity.audit((String)admin.get("username"), "UPDATE_USER", "USER", userId,
            "修改资料/启停账号：" + input.enabled());
    }
    
    public List<Map<String,Object>> audits() {
        identity.admin();
        return db.list("SELECT * FROM audit_logs ORDER BY id DESC LIMIT 500");
    }

    /** FR-23/24/25: counter definitions are documented so a booking request is not misread as successful usage. */
    public Map<String,Object> stats() {
        identity.admin();
        var now = LocalDateTime.now(clock);
        var today = now.toLocalDate();

        List<Map<String,Object>> bars = db.list("SELECT r.name,COUNT(b.id) AS bookings FROM rooms r "
            + "LEFT JOIN seats s ON s.room_id=r.id "
            + "LEFT JOIN reservations b ON b.seat_id=s.id AND b.start_at>=? AND b.start_at<? "
            + "GROUP BY r.id,r.name ORDER BY r.id", today.atStartOfDay(), today.plusDays(1).atStartOfDay());

        List<Map<String,Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            var day = today.minusDays(i);
            trend.add(Map.of(
                "date", day.toString(),
                "bookings", db.count("SELECT COUNT(*) FROM reservations WHERE start_at>=? AND start_at<?",
                    day.atStartOfDay(), day.plusDays(1).atStartOfDay()),
                "violations", db.count("SELECT COUNT(*) FROM violations WHERE revoked=FALSE "
                    + "AND created_at>=? AND created_at<?", day.atStartOfDay(), day.plusDays(1).atStartOfDay())));
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
