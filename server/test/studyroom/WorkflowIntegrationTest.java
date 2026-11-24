package studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import studyroom.platform.Store;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 8 组关键验收：身份边界、预约冲突、时间规则、签到签退、待核查、
 * 违约黑名单、投诉评价、并发唯一性。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "app.scheduling-enabled=false", "app.seed=false"
})
@AutoConfigureMockMvc
@Import(WorkflowIntegrationTest.TimeConfig.class)
class WorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Store db;
    @Autowired PasswordEncoder passwords;
    @Autowired BookingService bookings;
    @Autowired MutableClock clock;

    long alice, bob, room, s1, s2, s3, s4;
    final LocalDateTime now = LocalDateTime.of(2026, 10, 29, 9, 0);

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-10-29T01:00:00Z"));
        void set(LocalDateTime date) { instant.set(date.atZone(getZone()).toInstant()); }
        public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        public Instant instant() { return instant.get(); }
    }

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
        alice = createUser("alice", "STUDENT", hash);
        bob = createUser("bobby", "STUDENT", hash);
        createUser("admin", "ADMIN", hash);

        room = db.insert("INSERT INTO rooms(name,building,floor_no,open_time,close_time,description,enabled) "
            + "VALUES('测试阅览室','图书馆',2,'07:00','23:00','test',TRUE)");
        s1 = seat("01");
        s2 = seat("02");
        s3 = seat("03");
        s4 = seat("04");
    }

    long createUser(String name, String role, String hash) {
        return db.insert("INSERT INTO app_users(username,display_name,password_hash,role,enabled,created_at) "
            + "VALUES(?,?,?, ?,TRUE,?)", name, name, hash, role, now);
    }

    long seat(String code) {
        return db.insert("INSERT INTO seats(room_id,code,has_power,enabled) "
            + "VALUES(?,?,TRUE,TRUE)", room, code);
    }

    ResultActions postAs(String path, Object data, String who) throws Exception {
        return mvc.perform(post("/api" + path)
            .with(user(who).roles(who.equals("admin") ? "ADMIN" : "STUDENT"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(data)));
    }

    ResultActions putAs(String path, Object data, String who) throws Exception {
        return mvc.perform(put("/api" + path)
            .with(user(who).roles(who.equals("admin") ? "ADMIN" : "STUDENT"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(data)));
    }

    ResultActions getAs(String path, String who) throws Exception {
        return mvc.perform(get("/api" + path)
            .with(user(who).roles(who.equals("admin") ? "ADMIN" : "STUDENT")));
    }

    long book(String who, long seatId, LocalDateTime start, LocalDateTime end) throws Exception {
        var result = postAs("/reservations", new Requests.Booking(seatId, start, end), who)
            .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void publicEndpointsAndRoleBoundary() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized());
        getAs("/admin/users", "alice").andExpect(status().isForbidden());
        mvc.perform(post("/api/reservations")).andExpect(status().isForbidden());
    }

    @Test
    void realLoginPersistsSessionAndLogoutClearsIt() throws Exception {
        var login = mvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"Password123!\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.password_hash").doesNotExist())
            .andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.username").value("alice"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void seatAndUserOverlapAreRejectedButAdjacentSlotsWork() throws Exception {
        book("alice", s1, now.plusMinutes(5), now.plusHours(1));
        postAs("/reservations", new Requests.Booking(s1, now.plusMinutes(30), now.plusHours(2)), "bobby")
            .andExpect(status().isConflict());
        postAs("/reservations", new Requests.Booking(s2, now.plusMinutes(30), now.plusHours(2)), "alice")
            .andExpect(status().isConflict());
        book("bobby", s1, now.plusHours(1), now.plusHours(2));
    }

    @Test
    void timeRulesAndPerUserActiveLimit() throws Exception {
        for (var input : List.of(
            new Requests.Booking(s1, now.minusHours(1), now),
            new Requests.Booking(s1, now.plusMinutes(1), now.plusMinutes(10)),
            new Requests.Booking(s1, now.plusMinutes(1), now.plusHours(5)),
            new Requests.Booking(s1, now.plusDays(8), now.plusDays(8).plusHours(1)),
            new Requests.Booking(s1, now.withHour(22), now.plusDays(1).withHour(8)),
            new Requests.Booking(s1, now.plusDays(1).withHour(6), now.plusDays(1).withHour(7)))) {
            postAs("/reservations", input, "alice").andExpect(status().isBadRequest());
        }
        db.update("UPDATE seats SET enabled=FALSE WHERE id=?", s1);
        postAs("/reservations", new Requests.Booking(s1, now.plusMinutes(5), now.plusHours(1)), "alice")
            .andExpect(status().isConflict());

        db.update("UPDATE seats SET enabled=TRUE WHERE id=?", s1);
        book("alice", s1, now.plusHours(1), now.plusHours(2));
        book("alice", s2, now.plusHours(2), now.plusHours(3));
        book("alice", s3, now.plusHours(3), now.plusHours(4));
        postAs("/reservations", new Requests.Booking(s4, now.plusHours(4), now.plusHours(5)), "alice")
            .andExpect(status().isConflict());
    }

    @Test
    void cancelCheckInAndCheckOutFollowTheStateMachine() throws Exception {
        long id = book("alice", s1, now.plusMinutes(5), now.plusHours(1));
        postAs("/reservations/" + id + "/check-in", Map.of(), "alice").andExpect(status().isOk());
        clock.set(now.plusMinutes(40));
        postAs("/reservations/" + id + "/check-out", Map.of(), "alice").andExpect(status().isOk());
        assertThat(db.one("SELECT status FROM reservations WHERE id=?", id).get("status")).isEqualTo("COMPLETED");

        long second = book("alice", s2, now.plusHours(2), now.plusHours(3));
        postAs("/reservations/" + second + "/cancel", Map.of(), "alice").andExpect(status().isOk());
        postAs("/reservations/" + second + "/cancel", Map.of(), "alice").andExpect(status().isConflict());
        book("bobby", s2, now.plusHours(2), now.plusHours(3));

        long early = book("alice", s1, now.plusHours(4), now.plusHours(5));
        postAs("/reservations/" + early + "/check-in", Map.of(), "alice").andExpect(status().isConflict());
        clock.set(now.plusHours(4).plusMinutes(16));
        postAs("/reservations/" + early + "/check-in", Map.of(), "alice").andExpect(status().isConflict());
    }

    @Test
    void noShowGoesToPendingThenAdminConfirmsExactlyOnce() throws Exception {
        long id = book("alice", s1, now.plusHours(1), now.plusHours(2));
        clock.set(now.plusMinutes(75)); // 10:15，已超过 10:00 + 15 分钟宽限

        assertThat(bookings.sweep()).isEqualTo(1);
        assertThat(db.one("SELECT status FROM reservations WHERE id=?", id).get("status")).isEqualTo("PENDING");
        assertThat(db.count("SELECT COUNT(*) FROM violations WHERE reservation_id=?", id)).isZero();
        assertThat(bookings.sweep()).isZero();

        postAs("/admin/reservations/" + id + "/confirm-violation",
            new Requests.Reason("管理员确认未签到"), "admin").andExpect(status().isOk());
        assertThat(db.one("SELECT status FROM reservations WHERE id=?", id).get("status")).isEqualTo("VIOLATED");
        assertThat(db.count("SELECT COUNT(*) FROM violations WHERE reservation_id=?", id)).isEqualTo(1);

        postAs("/admin/reservations/" + id + "/confirm-violation",
            new Requests.Reason("重复确认"), "admin").andExpect(status().isConflict());
    }

    @Test
    void twoViolationsTriggerBlacklistAndReleaseRestoresBooking() throws Exception {
        long first = book("alice", s1, now.plusHours(1), now.plusHours(2));
        long second = book("alice", s2, now.plusHours(2), now.plusHours(3));

        postAs("/admin/violations", new Requests.Violation(first, "第一次违约"), "admin").andExpect(status().isOk());
        assertThat(db.count("SELECT COUNT(*) FROM blacklist_records WHERE user_id=? AND active=TRUE", alice)).isZero();
        postAs("/admin/violations", new Requests.Violation(second, "第二次违约"), "admin").andExpect(status().isOk());
        assertThat(db.count("SELECT COUNT(*) FROM blacklist_records WHERE user_id=? AND active=TRUE", alice)).isEqualTo(1);

        postAs("/reservations", new Requests.Booking(s3, now.plusHours(3), now.plusHours(4)), "alice")
            .andExpect(status().isForbidden());

        postAs("/admin/users/" + alice + "/blacklist/release",
            new Requests.Reason("申诉核实，提前解除"), "admin").andExpect(status().isOk());
        book("alice", s3, now.plusHours(3), now.plusHours(4));
    }

    @Test
    void complaintWithdrawReplyAndReviewWorkflow() throws Exception {
        long id = book("alice", s1, now.plusMinutes(5), now.plusHours(1));
        postAs("/reservations/" + id + "/check-in", Map.of(), "alice").andExpect(status().isOk());
        clock.set(now.plusMinutes(40));
        postAs("/reservations/" + id + "/check-out", Map.of(), "alice").andExpect(status().isOk());

        postAs("/reviews", new Requests.Review(id, 5, "安静整洁"), "alice").andExpect(status().isCreated());
        postAs("/reviews", new Requests.Review(id, 4, "重复评价"), "alice").andExpect(status().isConflict());

        var complaint = postAs("/complaints", new Requests.Complaint(null, "空调反馈", "温度偏低"), "alice")
            .andExpect(status().isCreated()).andReturn();
        long cid = json.readTree(complaint.getResponse().getContentAsString()).get("id").asLong();
        postAs("/complaints/" + cid + "/withdraw", Map.of(), "alice").andExpect(status().isOk());
        assertThat(db.one("SELECT status FROM complaints WHERE id=?", cid).get("status")).isEqualTo("WITHDRAWN");
        postAs("/complaints/" + cid + "/withdraw", Map.of(), "alice").andExpect(status().isConflict());

        var second = postAs("/complaints", new Requests.Complaint(id, "座位反馈", "桌面有灰"), "alice")
            .andExpect(status().isCreated()).andReturn();
        long cid2 = json.readTree(second.getResponse().getContentAsString()).get("id").asLong();
        putAs("/admin/complaints/" + cid2,
            new Requests.Reply("RESOLVED", "已安排清洁", "PENDING"), "admin").andExpect(status().isOk());
        putAs("/admin/complaints/" + cid2,
            new Requests.Reply("PROCESSING", "重复处理", "PENDING"), "admin").andExpect(status().isConflict());
        getAs("/complaints", "alice").andExpect(jsonPath("$[0].reply").value("已安排清洁"));
        getAs("/complaints", "bobby").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void resourcesWithActiveBookingsCannotBeChangedAndOwnershipIsChecked() throws Exception {
        long id = book("alice", s1, now.plusMinutes(5), now.plusHours(1));
        putAs("/admin/seats/" + s1, new Requests.Seat(room, "01", true, false), "admin")
            .andExpect(status().isConflict());
        putAs("/admin/rooms/" + room, new Requests.Room("测试阅览室", "图书馆", 2, "08:00", "22:00", "test", false, true), "admin")
            .andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/seats/" + s1).with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isConflict());

        postAs("/reservations/" + id + "/cancel", Map.of(), "bobby").andExpect(status().isForbidden());
        postAs("/complaints", new Requests.Complaint(id, "越权关联", "测试"), "bobby").andExpect(status().isForbidden());
        postAs("/reviews", new Requests.Review(id, 5, "越权评价"), "bobby").andExpect(status().isForbidden());
    }

    @Test
    void concurrentReservationsHaveOneWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String who : List.of("alice", "bobby")) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return postAs("/reservations",
                        new Requests.Booking(s1, now.plusMinutes(5), now.plusHours(1)), who)
                        .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (var future : futures) codes.add(future.get(15, TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(201, 409);
            assertThat(db.count("SELECT COUNT(*) FROM reservations")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
