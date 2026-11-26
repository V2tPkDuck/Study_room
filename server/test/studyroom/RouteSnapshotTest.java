package studyroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import studyroom.platform.Store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import studyroom.booking.BookingRequests;
import studyroom.credit.CreditRequests;
import studyroom.feedback.FeedbackRequests;
import studyroom.resource.ResourceRequests;
/**
 * 路由快照测试（防退化安全网）。
 *
 * <p>作用：把当前所有接口的"状态码 + 返回内容"录成基线文件，重构前后逐字段比对。
 * 任何一处业务行为被改坏，这里会立刻变红并指出是哪个用例。
 *
 * <p>用法：
 * <pre>
 *   cd server
 *   mvn -B -ntp test -Dsnapshot.update=true   # 第一次：生成/更新基线（会覆盖）
 *   mvn -B -ntp test                          # 之后：只比对，不写入
 * </pre>
 *
 * <p>判据只有一条：<b>所有用例的返回与基线完全一致</b>。
 * 基线存放在 {@code server/test/snapshots/}，必须提交进 Git。
 *
 * <p>本测试环境是确定性的（可变时钟固定在 2026-10-29 09:00 + 独立内存库 + 固定种子），
 * 因此 id、时间戳每次运行都相同，不需要做字段归一化。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:snapshots;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "app.scheduling-enabled=false",
    "app.seed=false"
})
@AutoConfigureMockMvc
@Import(RouteSnapshotTest.TimeConfig.class)
class RouteSnapshotTest {

    /** 是否处于"录制模式"：-Dsnapshot.update=true。 */
    private static final boolean UPDATE = Boolean.getBoolean("snapshot.update");
    private static final Path DIR = snapshotDir();

    private final List<String> mismatches = new ArrayList<>();
    private int recorded = 0;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Store db;
    @Autowired PasswordEncoder passwords;
    @Autowired MutableClock clock;

    final LocalDateTime now = LocalDateTime.of(2026, 10, 29, 9, 0);
    long aliceId, bobId, adminId, room, s1, s2, s3, s4;

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary
        MutableClock snapshotClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        final java.util.concurrent.atomic.AtomicReference<Instant> instant =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-29T01:00:00Z"));
        void set(LocalDateTime date) { instant.set(date.atZone(getZone()).toInstant()); }
        public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        public Instant instant() { return instant.get(); }
    }

    // ------------------------------------------------------------------
    // 测试数据：固定种子，保证每次运行 id 与时间完全一致
    // ------------------------------------------------------------------

    @BeforeEach
    void setup() {
        clock.set(now);
        for (String table : List.of("blacklist_records", "audit_logs", "reviews", "complaints",
                "violations", "reservations", "seats", "rooms", "app_users")) {
            db.update("DELETE FROM " + table);
        }
        db.update("DELETE FROM reservation_policy");
        db.update("INSERT INTO reservation_policy VALUES(1,7,4,15,15,3,2,7)");
        if (db.count("SELECT COUNT(*) FROM booking_mutex") == 0) {
            db.update("INSERT INTO booking_mutex(id) VALUES(1)");
        }

        String hash = passwords.encode("Password123!");
        aliceId = createUser("alice", "Alice", "STUDENT", hash);
        bobId = createUser("bobby", "Bobby", "STUDENT", hash);
        adminId = createUser("admin", "Admin", "ADMIN", hash);

        room = db.insert("INSERT INTO rooms(name,building,floor_no,open_time,close_time,description,enabled,is_quiet) "
            + "VALUES('测试阅览室','图书馆',2,'07:00','23:00','快照测试用',TRUE,FALSE)");
        s1 = seat("01");
        s2 = seat("02");
        s3 = seat("03");
        s4 = seat("04");
    }

    long createUser(String name, String displayName, String role, String hash) {
        return db.insert("INSERT INTO app_users(username,display_name,password_hash,role,enabled,created_at) "
            + "VALUES(?,?,?,?,TRUE,?)", name, displayName, hash, role, now);
    }

    long seat(String code) {
        return db.insert("INSERT INTO seats(room_id,code,has_power,enabled) VALUES(?,?,TRUE,TRUE)", room, code);
    }

    // ------------------------------------------------------------------
    // 请求封装：每个方法都会自动录制快照
    // ------------------------------------------------------------------

    private ObjectNode anonGet(String name, String path) throws Exception {
        return capture(name, mvc.perform(get("/api" + path)));
    }

    private ObjectNode anonPost(String name, String path, Object body) throws Exception {
        return capture(name, mvc.perform(post("/api" + path)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))));
    }

    private ObjectNode getAs(String name, String who, String path) throws Exception {
        return capture(name, mvc.perform(get("/api" + path).with(user(who).roles(role(who)))));
    }

    private ObjectNode postAs(String name, String who, String path, Object body) throws Exception {
        return capture(name, mvc.perform(post("/api" + path)
            .with(user(who).roles(role(who))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))));
    }

    private ObjectNode putAs(String name, String who, String path, Object body) throws Exception {
        return capture(name, mvc.perform(put("/api" + path)
            .with(user(who).roles(role(who))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))));
    }

    private ObjectNode deleteAs(String name, String who, String path) throws Exception {
        return capture(name, mvc.perform(delete("/api" + path)
            .with(user(who).roles(role(who))).with(csrf())));
    }

    private static String role(String who) { return "admin".equals(who) ? "ADMIN" : "STUDENT"; }

    private static long id(ObjectNode snap) {
        return snap.path("body").path("id").asLong();
    }

    // ------------------------------------------------------------------
    // 快照录制 / 比对
    // ------------------------------------------------------------------

    private ObjectNode capture(String name, ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        int status = result.getResponse().getStatus();
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        ObjectNode snap = json.createObjectNode();
        snap.put("status", status);
        if (text == null || text.isBlank()) snap.putNull("body");
        else snap.set("body", json.readTree(text));

        Path file = DIR.resolve(name + ".json");
        Files.createDirectories(DIR);

        if (UPDATE) {
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snap);
            recorded++;
            return snap;
        }
        if (!Files.exists(file)) {
            mismatches.add(name + "  —— 缺少基线文件（先用 -Dsnapshot.update=true 生成）");
            return snap;
        }
        JsonNode expected = json.readTree(file.toFile());
        if (!expected.equals(snap)) {
            Path actual = DIR.resolve(name + ".actual.json");
            json.writerWithDefaultPrettyPrinter().writeValue(actual.toFile(), snap);
            mismatches.add(name + "  —— 行为变了，实际结果见 " + actual.getFileName());
        }
        return snap;
    }

    /** 从当前目录向上找项目根，定位 server/test/snapshots。 */
    private static Path snapshotDir() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("server/pom.xml"))) return p.resolve("server/test/snapshots");
            if (Files.exists(p.resolve("pom.xml"))) return p.resolve("test/snapshots");
        }
        return Path.of("test/snapshots");
    }

    // ------------------------------------------------------------------
    // 用例脚本：顺序固定，因此数据状态也是确定的
    // ------------------------------------------------------------------

    @Test
    void allRoutesBehaveTheSame() throws Exception {
        // ---------- A 匿名与权限边界 ----------
        anonGet("01-匿名-健康检查", "/health");
        anonGet("02-匿名-查自习室", "/rooms");
        anonGet("03-匿名-当前用户", "/auth/me");
        anonGet("04-匿名-查我的预约", "/reservations");
        anonPost("05-匿名-预约", "/reservations",
            new BookingRequests.Booking(s1, now.plusHours(1), now.plusHours(2)));
        getAs("06-学生-查管理端用户", "alice", "/admin/users");
        getAs("07-学生-查管理端统计", "alice", "/admin/stats");
        getAs("08-管理员-查用户", "admin", "/admin/users");

        // ---------- B 学生主流程 ----------
        getAs("10-学生-查自习室", "alice", "/rooms");
        getAs("11-学生-查座位", "alice", "/rooms/" + room + "/seats");
        ObjectNode booking = postAs("12-学生-预约", "alice", "/reservations",
            new BookingRequests.Booking(s1, now.plusHours(1), now.plusHours(2)));
        long bookingId = id(booking);
        getAs("13-学生-我的预约", "alice", "/reservations");
        postAs("14-学生-重复预约同座同时段", "alice", "/reservations",
            new BookingRequests.Booking(s1, now.plusHours(1), now.plusHours(2)));
        postAs("15-学生-自己时段重叠", "alice", "/reservations",
            new BookingRequests.Booking(s2, now.plusMinutes(90), now.plusMinutes(150)));
        getAs("16-学生-查座位-指定时段", "alice",
            "/rooms/" + room + "/seats?start=" + now.plusHours(1) + "&end=" + now.plusHours(2));
        postAs("17-学生-取消他人预约", "bobby", "/reservations/" + bookingId + "/cancel", Map.of());
        postAs("18-学生-窗口外签到", "alice", "/reservations/" + bookingId + "/check-in", Map.of());
        clock.set(now.plusMinutes(50));
        postAs("19-学生-窗口内签到", "alice", "/reservations/" + bookingId + "/check-in", Map.of());
        postAs("20-学生-签退", "alice", "/reservations/" + bookingId + "/check-out", Map.of());
        ObjectNode review = postAs("21-学生-评价", "alice", "/reviews",
            new FeedbackRequests.Review(bookingId, 5, "环境安静"));
        long reviewId = id(review);
        postAs("22-学生-重复评价", "alice", "/reviews", new FeedbackRequests.Review(bookingId, 4, "再评一次"));
        getAs("23-学生-我的违约-空", "alice", "/violations");

        // ---------- C 待核查 / 违约 / 黑名单 ----------
        ObjectNode second = postAs("30-学生-预约第二单", "alice", "/reservations",
            new BookingRequests.Booking(s2, now.plusHours(2), now.plusHours(3)));
        long secondId = id(second);
        clock.set(now.plusHours(2).plusMinutes(20));
        postAs("31-管理员-巡检", "admin", "/admin/sweep", Map.of());
        getAs("32-管理员-待核查列表", "admin", "/admin/reservations?status=PENDING");
        getAs("33-学生-我的预约-含待核查", "alice", "/reservations");
        postAs("34-管理员-确认违约", "admin",
            "/admin/reservations/" + secondId + "/confirm-violation",
            new CreditRequests.Reason("超时未签到"));
        postAs("35-管理员-重复确认违约", "admin",
            "/admin/reservations/" + secondId + "/confirm-violation",
            new CreditRequests.Reason("重复确认"));
        getAs("36-管理员-违约列表", "admin", "/admin/violations");
        getAs("37-学生-我的违约", "alice", "/violations");
        postAs("38-管理员-对已违约再登记", "admin", "/admin/violations",
            new BookingRequests.Violation(secondId, "重复登记"));
        ObjectNode third = postAs("39-学生-预约第三单", "alice", "/reservations",
            new BookingRequests.Booking(s3, now.plusHours(4), now.plusHours(5)));
        long thirdId = id(third);
        postAs("40-管理员-手动登记违约", "admin", "/admin/violations",
            new BookingRequests.Violation(thirdId, "现场违规"));
        getAs("41-管理员-黑名单记录", "admin", "/admin/users/" + aliceId + "/blacklist");
        postAs("42-学生-黑名单期预约", "alice", "/reservations",
            new BookingRequests.Booking(s4, now.plusHours(6), now.plusHours(7)));
        postAs("43-管理员-解除黑名单", "admin",
            "/admin/users/" + aliceId + "/blacklist/release", new CreditRequests.Reason("申诉通过"));
        postAs("44-学生-解除后可预约", "alice", "/reservations",
            new BookingRequests.Booking(s4, now.plusHours(6), now.plusHours(7)));

        // ---------- D 投诉与评价管理 ----------
        ObjectNode complaint = postAs("50-学生-提交投诉", "alice", "/complaints",
            new FeedbackRequests.Complaint(bookingId, "座位问题", "桌面有灰"));
        long complaintId = id(complaint);
        getAs("51-学生-我的投诉", "alice", "/complaints");
        getAs("52-管理员-全部投诉", "admin", "/admin/complaints");
        putAs("53-管理员-回复投诉", "admin", "/admin/complaints/" + complaintId,
            new FeedbackRequests.Reply("RESOLVED", "已安排清洁", "PENDING"));
        putAs("54-管理员-用过期状态再回复", "admin", "/admin/complaints/" + complaintId,
            new FeedbackRequests.Reply("PROCESSING", "重复处理", "PENDING"));
        postAs("55-学生-撤回已解决投诉", "alice", "/complaints/" + complaintId + "/withdraw", Map.of());
        putAs("56-管理员-隐藏评价", "admin", "/admin/reviews/" + reviewId,
            new FeedbackRequests.Visibility(false));
        getAs("57-学生-公开评价列表", "alice", "/reviews");
        getAs("58-管理员-全部评价", "admin", "/admin/reviews");

        // ---------- E 资源 / 统计 / 审计 ----------
        getAs("60-管理员-自习室列表", "admin", "/admin/rooms");
        getAs("61-管理员-座位列表", "admin", "/admin/rooms/" + room + "/seats");
        postAs("62-管理员-新建自习室", "admin", "/admin/rooms",
            new ResourceRequests.Room("第二阅览室", "图书馆", 3, "08:00", "22:00", "快照新建", true, false));
        putAs("63-管理员-修改有有效预约的自习室", "admin", "/admin/rooms/" + room,
            new ResourceRequests.Room("测试阅览室", "图书馆", 2, "07:00", "23:00", "改描述", true, false));
        postAs("64-管理员-新建座位", "admin", "/admin/seats", new ResourceRequests.Seat(room, "05", true, true));
        deleteAs("65-管理员-删除有历史预约的座位", "admin", "/admin/seats/" + s1);
        getAs("66-管理员-统计", "admin", "/admin/stats");
        getAs("67-管理员-审计日志", "admin", "/admin/audits");
        getAs("68-管理员-搜索用户", "admin", "/admin/users?query=ali");
        postAs("69-管理员-新增学生", "admin", "/admin/users",
            new ResourceRequests.UserCreate("carol", "Carol", "Password123!"));
        putAs("70-管理员-修改学生", "admin", "/admin/users/" + bobId,
            new ResourceRequests.UserUpdate("Bobby2", true));
        putAs("71-管理员-停用自己", "admin", "/admin/users/" + adminId,
            new ResourceRequests.UserUpdate("Admin", false));

        // ---------- F 异常输入 ----------
        postAs("80-学生-取消不存在的预约", "alice", "/reservations/9999/cancel", Map.of());
        getAs("81-学生-非法状态筛选", "alice", "/reservations?status=FOO");
        postAs("82-学生-超出时长预约", "alice", "/reservations",
            new BookingRequests.Booking(s2, now.plusHours(20), now.plusHours(25)));
        postAs("83-学生-过去时间预约", "alice", "/reservations",
            new BookingRequests.Booking(s2, now.minusHours(2), now.minusHours(1)));

        report();
    }

    private void report() {
        if (UPDATE) {
            System.out.println("【快照】已录制/覆盖 " + recorded + " 个用例到 " + DIR);
            return;
        }
        if (mismatches.isEmpty()) {
            System.out.println("【快照】全部用例与基线一致");
            return;
        }
        mismatches.forEach(m -> System.out.println("【快照·不一致】" + m));
        assertThat(mismatches)
            .as("下列用例的返回与基线不一致，说明行为被改变（详见 test/snapshots/*.actual.json）")
            .isEmpty();
    }
}
