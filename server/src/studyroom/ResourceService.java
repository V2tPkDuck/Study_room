package studyroom;

import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static studyroom.BusinessException.require;
import static studyroom.Store.*;

/** 资源、用户、黑名单、统计和审计。
 *  需求追踪（仅代码注释）：FR-12/13 资源；FR-18 用户；FR-19 黑名单；FR-23/24 统计；QR-SEC-04 审计。 */
@Service
public class ResourceService {
    private final Store db;
    private final Accounts accounts;
    private final Clock clock;
    private final PasswordEncoder passwords;

    public ResourceService(Store db, Accounts accounts, Clock clock, PasswordEncoder passwords) {
        this.db = db;
        this.accounts = accounts;
        this.clock = clock;
        this.passwords = passwords;
    }

    @Transactional
    public long saveRoom(Long roomId, Requests.Room input) {
        db.lock();
        var admin = accounts.admin();
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
        accounts.audit((String)admin.get("username"), "SAVE_ROOM", "ROOM", roomId, input.name());
        return roomId;
    }

    @Transactional
    public long saveSeat(Long seatId, Requests.Seat input) {
        db.lock();
        var admin = accounts.admin();
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
        accounts.audit((String)admin.get("username"), "SAVE_SEAT", "SEAT", seatId, input.code());
        return seatId;
    }

    @Transactional
    public void delete(String type, long resource) {
        db.lock();
        var admin = accounts.admin();
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
        accounts.audit((String)admin.get("username"), "DELETE_RESOURCE",
            type.toUpperCase(Locale.ROOT), resource, "删除无历史记录的资源");
    }

    public List<Map<String,Object>> users(String query) {
        accounts.admin();
        String q = query == null ? "" : query;
        return db.list("SELECT " + Accounts.PUBLIC_FIELDS + " FROM app_users "
            + "WHERE username LIKE ? OR display_name LIKE ? ORDER BY id DESC LIMIT 500",
            "%" + q + "%", "%" + q + "%");
    }

    /** FR-18：管理员新增学生账号，替代学生自助注册（报告未列注册为 FR）。 */
    @Transactional
    public long createUser(Requests.UserCreate input) {
        db.lock();
        var admin = accounts.admin();
        require(db.count("SELECT COUNT(*) FROM app_users WHERE username=?", input.username()) == 0,
            409, "账号已存在");
        var now = LocalDateTime.now(clock);
        long userId = db.insert("INSERT INTO app_users(username,display_name,password_hash,role,enabled,created_at) "
            + "VALUES(?,?,?,'STUDENT',TRUE,?)", input.username(), input.displayName().trim(),
            passwords.encode(input.password()), now);
        accounts.audit((String)admin.get("username"), "CREATE_USER", "USER", userId, input.username());
        return userId;
    }

    @Transactional
    public void updateUser(long userId, Requests.UserUpdate input) {
        db.lock();
        var admin = accounts.admin();
        db.one("SELECT id FROM app_users WHERE id=?", userId);
        require(id(admin, "id") != userId || input.enabled(), 400, "不能停用当前登录账号");
        db.update("UPDATE app_users SET display_name=?,enabled=? WHERE id=?",
            input.displayName().trim(), input.enabled(), userId);
        accounts.audit((String)admin.get("username"), "UPDATE_USER", "USER", userId,
            "修改资料/启停账号：" + input.enabled());
    }

    /** FR-19：管理员手工加入黑名单，写入独立记录，便于答辩展示原因和起止。 */
    @Transactional
    public long blacklist(long userId, Requests.Blacklist input) {
        db.lock();
        var admin = accounts.admin();
        db.one("SELECT id FROM app_users WHERE id=?", userId);
        var now = LocalDateTime.now(clock);
        var end = now.plusDays(input.days());
        long record = db.insert("INSERT INTO blacklist_records(user_id,reason,start_at,end_at,created_by,active) "
            + "VALUES(?,?,?,?,?,TRUE)", userId, input.reason(), now, end, admin.get("username"));
        var user = db.one("SELECT blacklist_until FROM app_users WHERE id=?", userId);
        var current = time(user, "blacklist_until");
        if (current == null || current.isBefore(end)) {
            db.update("UPDATE app_users SET blacklist_until=? WHERE id=?", end, userId);
        }
        accounts.audit((String)admin.get("username"), "BLACKLIST", "BLACKLIST", record, input.reason());
        return record;
    }

    /** 解除某学生的全部有效黑名单记录，并重算账号上的截止时间。 */
    @Transactional
    public void releaseBlacklist(long userId, String reason) {
        db.lock();
        var admin = accounts.admin();
        db.one("SELECT id FROM app_users WHERE id=?", userId);
        long active = db.count("SELECT COUNT(*) FROM blacklist_records WHERE user_id=? AND active=TRUE", userId);
        require(active > 0, 409, "该学生当前没有有效黑名单");
        var now = LocalDateTime.now(clock);
        db.update("UPDATE blacklist_records SET active=FALSE,released_at=?,released_by=?,release_reason=? "
            + "WHERE user_id=? AND active=TRUE", now, admin.get("username"), reason, userId);
        var row = db.one("SELECT MAX(end_at) AS max_end FROM blacklist_records "
            + "WHERE user_id=? AND active=TRUE AND end_at>?", userId, now);
        db.update("UPDATE app_users SET blacklist_until=? WHERE id=?", row.get("max_end"), userId);
        accounts.audit((String)admin.get("username"), "RELEASE_BLACKLIST", "BLACKLIST", userId, reason);
    }

    public List<Map<String,Object>> blacklistRecords(long userId) {
        accounts.admin();
        return db.list("SELECT * FROM blacklist_records WHERE user_id=? ORDER BY id DESC LIMIT 200", userId);
    }

    public List<Map<String,Object>> audits() {
        accounts.admin();
        return db.list("SELECT * FROM audit_logs ORDER BY id DESC LIMIT 500");
    }

    /** FR-23/24/25：统计口径在页面和文档中写明，避免把“预约需求”误读成“成功使用”。 */
    public Map<String,Object> stats() {
        accounts.admin();
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
