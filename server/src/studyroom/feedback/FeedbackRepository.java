package studyroom.feedback;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Repository;
import studyroom.platform.Store;

/** Sole SQL entry for complaints and reviews. */
@Repository
public class FeedbackRepository {
    private final Store db;

    public FeedbackRepository(Store db) {
        this.db = db;
    }

    // ---------- complaints ----------

    List<Map<String, Object>> complaints(boolean admin, long userId) {
        return db.list("SELECT c.*,u.username,u.display_name FROM complaints c "
                + "JOIN app_users u ON u.id=c.user_id "
                + (admin ? "" : "WHERE c.user_id=? ")
                + "ORDER BY c.id DESC LIMIT 500", admin ? new Object[] {} : new Object[] { userId });
    }

    Map<String, Object> complaint(long complaintId) {
        return db.one("SELECT * FROM complaints WHERE id=?", complaintId);
    }

    long insertComplaint(long userId, Long reservationId, String topic, String content, LocalDateTime now) {
        return db.insert(
                "INSERT INTO complaints(user_id,reservation_id,topic,content,status,reply,created_at,updated_at) "
                        + "VALUES(?,?,?,?,'PENDING','',?,?)",
                userId, reservationId, topic, content, now, now);
    }

    void withdraw(long complaintId, LocalDateTime now) {
        db.update("UPDATE complaints SET status='WITHDRAWN',updated_at=? WHERE id=?", now, complaintId);
    }

    void reply(long complaintId, String status, String reply, long adminId, LocalDateTime now) {
        db.update("UPDATE complaints SET status=?,reply=?,replied_by=?,updated_at=? WHERE id=?",
                status, reply, adminId, now, complaintId);
    }

    // ---------- reviews ----------

    long insertReview(long userId, long reservationId, int rating, String content, LocalDateTime now) {
        return db.insert("INSERT INTO reviews(user_id,reservation_id,rating,content,visible,created_at) "
                + "VALUES(?,?,?,?,TRUE,?)", userId, reservationId, rating, content, now);
    }

    long countReviewsOfReservation(long reservationId) {
        return db.count("SELECT COUNT(*) FROM reviews WHERE reservation_id=?", reservationId);
    }

    void requireReview(long reviewId) {
        db.one("SELECT id FROM reviews WHERE id=?", reviewId);
    }

    void setVisible(long reviewId, boolean visible) {
        db.update("UPDATE reviews SET visible=? WHERE id=?", visible, reviewId);
    }

    List<Map<String, Object>> reviews(boolean admin) {
        // Students only see public reviews and display names, never other accounts or
        // user ids.
        return db.list("SELECT v.id,v.reservation_id,v.rating,v.content,v.visible,v.created_at,"
                + "u.display_name,r.name AS room_name,s.code AS seat_code FROM reviews v "
                + "JOIN app_users u ON u.id=v.user_id JOIN reservations b ON b.id=v.reservation_id "
                + "JOIN seats s ON s.id=b.seat_id JOIN rooms r ON r.id=s.room_id "
                + (admin ? "" : "WHERE v.visible=TRUE ") + "ORDER BY v.id DESC LIMIT 500");
    }
}