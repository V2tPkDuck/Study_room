package studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import studyroom.feedback.FeedbackRequests;
import studyroom.platform.Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:feedback_regression;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.scheduling-enabled=false", "app.seed=false"
})
@AutoConfigureMockMvc
class FeedbackRegressionIntegrationTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 29, 9, 0);
    private static final String OWNER = "owner";
    private static final String OTHER = "other";
    private static final String ADMIN = "admin";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Store db;
    private long ownerId;
    private long seatId;

    @BeforeEach
    void seedFixtures() {
        for (String table : List.of("audit_logs", "reviews", "complaints", "reservations", "seats", "rooms", "app_users")) {
            db.update("DELETE FROM " + table);
        }
        ownerId = createUser(OWNER, "STUDENT");
        createUser(OTHER, "STUDENT");
        createUser(ADMIN, "ADMIN");
        long roomId = db.insert("INSERT INTO rooms(name,building,floor_no,open_time,close_time,enabled) "
            + "VALUES('Test room','Library',1,'07:00','23:00',TRUE)");
        seatId = db.insert("INSERT INTO seats(room_id,code,has_power,enabled) VALUES(?,'A1',TRUE,TRUE)", roomId);
    }

    @Test
    void complaintWithoutReservationPersistsTrimmedTopicAndCreationAudit() throws Exception {
        long id = createComplaint(null, "  Lighting  ");
        assertThat(complaint(id))
            .containsEntry("topic", "Lighting").containsEntry("status", "PENDING")
            .containsEntry("user_id", ownerId).containsEntry("reservation_id", null);
        assertThat(audits("COMPLAINT", id)).isEqualTo(1);
    }

    @Test
    void complaintCanBeLinkedToOwnersReservation() throws Exception {
        long reservation = reservation("BOOKED");
        long id = createComplaint(reservation, "Lighting");
        assertThat(complaint(id)).containsEntry("reservation_id", reservation);
    }

    @Test
    void unknownReservationCannotLeaveComplaintOrCreationAudit() throws Exception {
        submit(OWNER, post("/api/complaints"), new FeedbackRequests.Complaint(Long.MAX_VALUE, "Lighting", "Dim"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").isString());
        assertThat(db.count("SELECT COUNT(*) FROM complaints")).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='COMPLAINT'")).isZero();
    }

    @Test
    void administratorListingIncludesComplaintsFromDifferentStudents() throws Exception {
        createComplaint(null, "Lighting");
        submit(OTHER, post("/api/complaints"), new FeedbackRequests.Complaint(null, "Noise", "Loud"))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/admin/complaints").with(user(ADMIN).roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/complaints").with(user(OTHER).roles("STUDENT")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].topic").value("Noise"));
    }

    @Test
    void withdrawalByAnotherStudentIsRejectedWithoutMutation() throws Exception {
        long id = createComplaint(null, "Lighting");
        var before = complaint(id);
        submit(OTHER, post("/api/complaints/{id}/withdraw", id), Map.of())
            .andExpect(status().isForbidden());
        assertThat(complaint(id)).isEqualTo(before);
        assertThat(audits("WITHDRAW_COMPLAINT", id)).isZero();
    }

    @Test
    void processingComplaintCannotBeWithdrawn() throws Exception {
        long id = createComplaint(null, "Lighting");
        reply(id, "PROCESSING", "Checking", "PENDING").andExpect(status().isOk());
        var before = complaint(id);
        submit(OWNER, post("/api/complaints/{id}/withdraw", id), Map.of()).andExpect(status().isConflict());
        assertThat(complaint(id)).isEqualTo(before);
        assertThat(audits("WITHDRAW_COMPLAINT", id)).isZero();
    }

    @Test
    void staleReplyLeavesLatestReplyAndAuditIntact() throws Exception {
        long id = createComplaint(null, "Lighting");
        reply(id, "PROCESSING", "Checking", "PENDING").andExpect(status().isOk());
        var before = complaint(id);
        reply(id, "RESOLVED", "Stale form", "PENDING").andExpect(status().isConflict());
        assertThat(complaint(id)).isEqualTo(before).containsEntry("reply", "Checking");
        assertThat(audits("REPLY_COMPLAINT", id)).isEqualTo(1);
    }

    @Test
    void missingExpectedStatusReturnsValidationErrorBeforeAnyWrite() throws Exception {
        long id = createComplaint(null, "Lighting");
        var before = complaint(id);
        submit(ADMIN, put("/api/admin/complaints/{id}", id), Map.of("status", "RESOLVED", "reply", "Done"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        assertThat(complaint(id)).isEqualTo(before);
        assertThat(audits("REPLY_COMPLAINT", id)).isZero();
    }

    @Test
    void resolvedComplaintCannotBeReopenedEvenWithCurrentExpectedStatus() throws Exception {
        long id = createComplaint(null, "Lighting");
        reply(id, "RESOLVED", "Done", "PENDING").andExpect(status().isOk());
        reply(id, "PROCESSING", "Reopen", "RESOLVED").andExpect(status().isConflict());
        assertThat(complaint(id)).containsEntry("status", "RESOLVED").containsEntry("reply", "Done");
        assertThat(audits("REPLY_COMPLAINT", id)).isEqualTo(1);
    }

    @Test
    void withdrawnComplaintCannotBeReopenedEvenWithCurrentExpectedStatus() throws Exception {
        long id = createComplaint(null, "Lighting");
        submit(OWNER, post("/api/complaints/{id}/withdraw", id), Map.of()).andExpect(status().isOk());
        reply(id, "PROCESSING", "Reopen", "WITHDRAWN").andExpect(status().isConflict());
        assertThat(complaint(id)).containsEntry("status", "WITHDRAWN");
        assertThat(audits("WITHDRAW_COMPLAINT", id)).isEqualTo(1);
        assertThat(audits("REPLY_COMPLAINT", id)).isZero();
    }

    @Test
    void reviewOfUpcomingReservationDoesNotPersist() throws Exception {
        long reservation = reservation("BOOKED");
        submit(OWNER, post("/api/reviews"), new FeedbackRequests.Review(reservation, 5, "Quiet"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isString());
        assertThat(db.count("SELECT COUNT(*) FROM reviews WHERE reservation_id=?", reservation)).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='REVIEW'")).isZero();
    }

    @Test
    void moderatorCanHideAndRestoreReviewWhileRetainingAudit() throws Exception {
        long id = createReview(reservation("COMPLETED"));
        submit(ADMIN, put("/api/admin/reviews/{id}", id), new FeedbackRequests.Visibility(false))
            .andExpect(status().isOk());
        mvc.perform(get("/api/reviews").with(user(OTHER).roles("STUDENT")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/admin/reviews").with(user(ADMIN).roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].visible").value(false));
        submit(ADMIN, put("/api/admin/reviews/{id}", id), new FeedbackRequests.Visibility(true))
            .andExpect(status().isOk());
        mvc.perform(get("/api/reviews").with(user(OTHER).roles("STUDENT")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].content").value("Quiet"));
        assertThat(audits("MODERATE_REVIEW", id)).isEqualTo(2);
    }

    @Test
    void hidingReviewDoesNotAllowSecondReviewOfSameReservation() throws Exception {
        long reservation = reservation("COMPLETED");
        long id = createReview(reservation);
        submit(ADMIN, put("/api/admin/reviews/{id}", id), new FeedbackRequests.Visibility(false))
            .andExpect(status().isOk());
        submit(OWNER, post("/api/reviews"), new FeedbackRequests.Review(reservation, 4, "Again"))
            .andExpect(status().isConflict());
        assertThat(db.count("SELECT COUNT(*) FROM reviews WHERE reservation_id=?", reservation)).isEqualTo(1);
        assertThat(audits("REVIEW", id)).isEqualTo(1);
    }

    private long createUser(String name, String role) {
        return db.insert("INSERT INTO app_users(username,display_name,password_hash,role,enabled,created_at) "
            + "VALUES(?,?,?, ?,TRUE,?)", name, name, "unused-by-mock-auth", role, START);
    }

    private long reservation(String state) {
        return db.insert("INSERT INTO reservations(user_id,seat_id,start_at,end_at,status,created_at) VALUES(?,?,?,?,?,?)",
            ownerId, seatId, START, START.plusHours(1), state, START.minusHours(1));
    }

    private long createComplaint(Long reservation, String topic) throws Exception {
        return createdId(submit(OWNER, post("/api/complaints"), new FeedbackRequests.Complaint(reservation, topic, "Dim")));
    }

    private long createReview(long reservation) throws Exception {
        return createdId(submit(OWNER, post("/api/reviews"), new FeedbackRequests.Review(reservation, 5, "Quiet")));
    }

    private long createdId(ResultActions response) throws Exception {
        return json.readTree(response.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
            .get("id").asLong();
    }

    private ResultActions reply(long id, String state, String text, String expected) throws Exception {
        return submit(ADMIN, put("/api/admin/complaints/{id}", id), new FeedbackRequests.Reply(state, text, expected));
    }

    private ResultActions submit(String actor, MockHttpServletRequestBuilder request, Object payload) throws Exception {
        return mvc.perform(request.with(user(actor).roles(actor.equals(ADMIN) ? "ADMIN" : "STUDENT"))
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)));
    }

    private Map<String, Object> complaint(long id) {
        return db.one("SELECT * FROM complaints WHERE id=?", id);
    }

    private long audits(String action, long id) {
        return db.count("SELECT COUNT(*) FROM audit_logs WHERE action=? AND target_id=?", action, id);
    }
}
