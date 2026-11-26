package studyroom.feedback;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studyroom.Requests;
import studyroom.platform.IdentityService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;
import static studyroom.platform.Store.id;

/** FR-10/11/21/22: complaints, withdrawal and administrator replies. */
@Service
public class ComplaintService {
    private final Store db;
    private final IdentityService identity;
    private final Clock clock;
    private final FeedbackRepository feedback;

    public ComplaintService(Store db, IdentityService identity, Clock clock, FeedbackRepository feedback) {
        this.db = db;
        this.identity = identity;
        this.clock = clock;
        this.feedback = feedback;
    }

    public List<Map<String, Object>> complaints(boolean admin) {
        var user = admin ? identity.admin() : identity.current();
        return feedback.complaints(admin, id(user, "id"));
    }

    @Transactional
    public long complain(Requests.Complaint input) {
        var user = identity.current();
        if (input.reservationId() != null) {
            var booking = db.one("SELECT user_id FROM reservations WHERE id=?", input.reservationId());
            require(id(user, "id") == id(booking, "user_id"), 403, "只能关联自己的预约");
        }
        var now = LocalDateTime.now(clock);
        long result = feedback.insertComplaint(id(user, "id"), input.reservationId(),
                input.topic().trim(), input.content(), now);
        identity.audit((String) user.get("username"), "COMPLAINT", "COMPLAINT", result, input.topic());
        return result;
    }

    /**
     * UC-08: the author may withdraw a complaint until an administrator handles it.
     */
    @Transactional
    public void withdraw(long complaint) {
        var user = identity.current();
        var c = feedback.complaint(complaint);
        require(id(user, "id") == id(c, "user_id"), 403, "只能撤回自己的投诉");
        require("PENDING".equals(c.get("status")), 409, "只有“待处理”的投诉可以撤回");
        feedback.withdraw(complaint, LocalDateTime.now(clock));
        identity.audit((String) user.get("username"), "WITHDRAW_COMPLAINT", "COMPLAINT", complaint, "学生撤回");
    }

    /**
     * Administrator reply; a mismatched expectedStatus means someone else already
     * handled it (409).
     */
    @Transactional
    public void reply(long complaint, Requests.Reply input) {
        var admin = identity.admin();
        var c = feedback.complaint(complaint);
        require(input.expectedStatus().equals(c.get("status")), 409, "投诉状态已变化，请刷新后重试");
        require(Set.of("PENDING", "PROCESSING").contains(c.get("status")),
                409, "已解决或已撤回的投诉不能再处理");
        feedback.reply(complaint, input.status(), input.reply(), id(admin, "id"), LocalDateTime.now(clock));
        identity.audit((String) admin.get("username"), "REPLY_COMPLAINT", "COMPLAINT", complaint,
                "状态：" + input.status());
    }
}