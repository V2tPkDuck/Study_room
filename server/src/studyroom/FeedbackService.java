package studyroom;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static studyroom.platform.Store.*;
import studyroom.platform.IdentityService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;

/** Complaints and usage reviews.
 *  Requirement map: FR-09 review, FR-10/11 complaint, FR-20 review moderation, FR-21/22 complaint handling. */
@Service
public class FeedbackService {
    private final Store db;
    private final IdentityService identity;
    private final Clock clock;

    public FeedbackService(Store db, IdentityService identity, Clock clock) {
        this.db = db;
        this.identity = identity;
        this.clock = clock;
    }

    public List<Map<String,Object>> complaints(boolean admin) {
        var user = admin ? identity.admin() : identity.current();
        return db.list("SELECT c.*,u.username,u.display_name FROM complaints c "
            + "JOIN app_users u ON u.id=c.user_id "
            + (admin ? "" : "WHERE c.user_id=? ")
            + "ORDER BY c.id DESC LIMIT 500", admin ? new Object[]{} : new Object[]{user.get("id")});
    }

    @Transactional
    public long complain(Requests.Complaint input) {
        var user = identity.current();
        if (input.reservationId() != null) {
            var booking = db.one("SELECT user_id FROM reservations WHERE id=?", input.reservationId());
            require(id(user, "id") == id(booking, "user_id"), 403, "只能关联自己的预约");
        }
        var now = LocalDateTime.now(clock);
        long result = db.insert("INSERT INTO complaints(user_id,reservation_id,topic,content,status,reply,created_at,updated_at) "
            + "VALUES(?,?,?,?,'PENDING','',?,?)", user.get("id"), input.reservationId(),
            input.topic().trim(), input.content(), now, now);
        identity.audit((String)user.get("username"), "COMPLAINT", "COMPLAINT", result, input.topic());
        return result;
    }

    /** UC-08: the author may withdraw a complaint until an administrator handles it. */
    @Transactional
    public void withdraw(long complaint) {
        var user = identity.current();
        var c = db.one("SELECT * FROM complaints WHERE id=?", complaint);
        require(id(user, "id") == id(c, "user_id"), 403, "只能撤回自己的投诉");
        require("PENDING".equals(c.get("status")), 409, "只有“待处理”的投诉可以撤回");
        db.update("UPDATE complaints SET status='WITHDRAWN',updated_at=? WHERE id=?",
            LocalDateTime.now(clock), complaint);
        identity.audit((String)user.get("username"), "WITHDRAW_COMPLAINT", "COMPLAINT", complaint, "学生撤回");
    }

    /** Administrator reply; a mismatched expectedStatus means someone else already handled it (409). */
    @Transactional
    public void reply(long complaint, Requests.Reply input) {
        var admin = identity.admin();
        var c = db.one("SELECT * FROM complaints WHERE id=?", complaint);
        require(input.expectedStatus().equals(c.get("status")),
            409, "投诉状态已变化，请刷新后重试");
        require(Set.of("PENDING", "PROCESSING").contains(c.get("status")),
            409, "已解决或已撤回的投诉不能再处理");
        db.update("UPDATE complaints SET status=?,reply=?,replied_by=?,updated_at=? WHERE id=?",
            input.status(), input.reply(), admin.get("id"), LocalDateTime.now(clock), complaint);
        identity.audit((String)admin.get("username"), "REPLY_COMPLAINT", "COMPLAINT", complaint,
            "状态：" + input.status());
    }

    @Transactional
    public long review(Requests.Review input) {
        var user = identity.current();
        var booking = db.one("SELECT * FROM reservations WHERE id=?", input.reservationId());
        require(id(booking, "user_id") == id(user, "id"), 403, "只能评价自己的预约");
        require("COMPLETED".equals(booking.get("status")), 409, "完成使用后才能评价");
        require(db.count("SELECT COUNT(*) FROM reviews WHERE reservation_id=?", input.reservationId()) == 0,
            409, "该预约已经评价过");
        long result = db.insert("INSERT INTO reviews(user_id,reservation_id,rating,content,visible,created_at) "
            + "VALUES(?,?,?,?,TRUE,?)", user.get("id"), input.reservationId(), input.rating(),
            input.content(), LocalDateTime.now(clock));
        identity.audit((String)user.get("username"), "REVIEW", "REVIEW", result, "提交评价");
        return result;
    }

    public List<Map<String,Object>> reviews(boolean admin) {
        if (admin) identity.admin(); else identity.current();
        // Students only see public reviews and display names, never other accounts or user ids.
        return db.list("SELECT v.id,v.reservation_id,v.rating,v.content,v.visible,v.created_at,"
            + "u.display_name,r.name AS room_name,s.code AS seat_code FROM reviews v "
            + "JOIN app_users u ON u.id=v.user_id JOIN reservations b ON b.id=v.reservation_id "
            + "JOIN seats s ON s.id=b.seat_id JOIN rooms r ON r.id=s.room_id "
            + (admin ? "" : "WHERE v.visible=TRUE ") + "ORDER BY v.id DESC LIMIT 500");
    }

    @Transactional
    public void visibility(long review, boolean visible) {
        var admin = identity.admin();
        db.one("SELECT id FROM reviews WHERE id=?", review);
        db.update("UPDATE reviews SET visible=? WHERE id=?", visible, review);
        identity.audit((String)admin.get("username"), "MODERATE_REVIEW", "REVIEW", review, "可见：" + visible);
    }
}
