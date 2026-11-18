package studyroom;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static studyroom.Store.*;
import static studyroom.BusinessException.require;

/** 投诉反馈与使用评价。
 *  需求追踪（仅代码注释）：FR-09 评价；FR-10/11 投诉反馈；FR-20 评价管理；FR-21/22 投诉处理。 */
@Service
public class FeedbackService {
    private final Store db;
    private final Accounts accounts;
    private final Clock clock;

    public FeedbackService(Store db, Accounts accounts, Clock clock) {
        this.db = db;
        this.accounts = accounts;
        this.clock = clock;
    }

    public List<Map<String,Object>> complaints(boolean admin) {
        var user = admin ? accounts.admin() : accounts.current();
        return db.list("SELECT c.*,u.username,u.display_name FROM complaints c "
            + "JOIN app_users u ON u.id=c.user_id "
            + (admin ? "" : "WHERE c.user_id=? ")
            + "ORDER BY c.id DESC LIMIT 500", admin ? new Object[]{} : new Object[]{user.get("id")});
    }

    @Transactional
    public long complain(Requests.Complaint input) {
        var user = accounts.current();
        if (input.reservationId() != null) {
            var booking = db.one("SELECT user_id FROM reservations WHERE id=?", input.reservationId());
            require(id(user, "id") == id(booking, "user_id"), 403, "只能关联自己的预约");
        }
        var now = LocalDateTime.now(clock);
        long result = db.insert("INSERT INTO complaints(user_id,reservation_id,topic,content,status,reply,created_at,updated_at) "
            + "VALUES(?,?,?,?,'PENDING','',?,?)", user.get("id"), input.reservationId(),
            input.topic().trim(), input.content(), now, now);
        accounts.audit((String)user.get("username"), "COMPLAINT", "COMPLAINT", result, input.topic());
        return result;
    }

    /** UC-08：投诉在管理员处理前允许本人撤回。 */
    @Transactional
    public void withdraw(long complaint) {
        var user = accounts.current();
        var c = db.one("SELECT * FROM complaints WHERE id=?", complaint);
        require(id(user, "id") == id(c, "user_id"), 403, "只能撤回自己的投诉");
        require("PENDING".equals(c.get("status")), 409, "只有“待处理”的投诉可以撤回");
        db.update("UPDATE complaints SET status='WITHDRAWN',updated_at=? WHERE id=?",
            LocalDateTime.now(clock), complaint);
        accounts.audit((String)user.get("username"), "WITHDRAW_COMPLAINT", "COMPLAINT", complaint, "学生撤回");
    }

    /** 管理员回复；expectedStatus 不一致时提示刷新，避免覆盖别人的处理结果。 */
    @Transactional
    public void reply(long complaint, Requests.Reply input) {
        var admin = accounts.admin();
        var c = db.one("SELECT * FROM complaints WHERE id=?", complaint);
        require(input.expectedStatus().equals(c.get("status")),
            409, "投诉状态已变化，请刷新后重试");
        require(Set.of("PENDING", "PROCESSING").contains(c.get("status")),
            409, "已解决或已撤回的投诉不能再处理");
        db.update("UPDATE complaints SET status=?,reply=?,replied_by=?,updated_at=? WHERE id=?",
            input.status(), input.reply(), admin.get("id"), LocalDateTime.now(clock), complaint);
        accounts.audit((String)admin.get("username"), "REPLY_COMPLAINT", "COMPLAINT", complaint,
            "状态：" + input.status());
    }

    @Transactional
    public long review(Requests.Review input) {
        var user = accounts.current();
        var booking = db.one("SELECT * FROM reservations WHERE id=?", input.reservationId());
        require(id(booking, "user_id") == id(user, "id"), 403, "只能评价自己的预约");
        require("COMPLETED".equals(booking.get("status")), 409, "完成使用后才能评价");
        require(db.count("SELECT COUNT(*) FROM reviews WHERE reservation_id=?", input.reservationId()) == 0,
            409, "该预约已经评价过");
        long result = db.insert("INSERT INTO reviews(user_id,reservation_id,rating,content,visible,created_at) "
            + "VALUES(?,?,?,?,TRUE,?)", user.get("id"), input.reservationId(), input.rating(),
            input.content(), LocalDateTime.now(clock));
        accounts.audit((String)user.get("username"), "REVIEW", "REVIEW", result, "提交评价");
        return result;
    }

    public List<Map<String,Object>> reviews(boolean admin) {
        if (admin) accounts.admin(); else accounts.current();
        // 学生只看到公开评价和昵称，不返回其他用户的账号、用户 ID。
        return db.list("SELECT v.id,v.reservation_id,v.rating,v.content,v.visible,v.created_at,"
            + "u.display_name,r.name AS room_name,s.code AS seat_code FROM reviews v "
            + "JOIN app_users u ON u.id=v.user_id JOIN reservations b ON b.id=v.reservation_id "
            + "JOIN seats s ON s.id=b.seat_id JOIN rooms r ON r.id=s.room_id "
            + (admin ? "" : "WHERE v.visible=TRUE ") + "ORDER BY v.id DESC LIMIT 500");
    }

    @Transactional
    public void visibility(long review, boolean visible) {
        var admin = accounts.admin();
        db.one("SELECT id FROM reviews WHERE id=?", review);
        db.update("UPDATE reviews SET visible=? WHERE id=?", visible, review);
        accounts.audit((String)admin.get("username"), "MODERATE_REVIEW", "REVIEW", review, "可见：" + visible);
    }
}
