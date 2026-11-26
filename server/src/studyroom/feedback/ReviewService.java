package studyroom.feedback;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studyroom.platform.IdentityService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;
import static studyroom.platform.Store.id;

/** FR-09/FR-20: reviews, public listing and visibility moderation. */
@Service
public class ReviewService {
    private final Store db;
    private final IdentityService identity;
    private final Clock clock;
    private final FeedbackRepository feedback;

    public ReviewService(Store db, IdentityService identity, Clock clock, FeedbackRepository feedback) {
        this.db = db;
        this.identity = identity;
        this.clock = clock;
        this.feedback = feedback;
    }

    @Transactional
    public long review(FeedbackRequests.Review input) {
        var user = identity.current();
        var booking = db.one("SELECT * FROM reservations WHERE id=?", input.reservationId());
        require(id(booking, "user_id") == id(user, "id"), 403, "只能评价自己的预约");
        require("COMPLETED".equals(booking.get("status")), 409, "完成使用后才能评价");
        require(feedback.countReviewsOfReservation(input.reservationId()) == 0, 409, "该预约已经评价过");

        long result = feedback.insertReview(id(user, "id"), input.reservationId(), input.rating(),
                input.content(), LocalDateTime.now(clock));
        identity.audit((String) user.get("username"), "REVIEW", "REVIEW", result, "提交评价");
        return result;
    }

    public List<Map<String, Object>> reviews(boolean admin) {
        if (admin)
            identity.admin();
        else
            identity.current();
        return feedback.reviews(admin);
    }

    @Transactional
    public void visibility(long review, boolean visible) {
        var admin = identity.admin();
        feedback.requireReview(review);
        feedback.setVisible(review, visible);
        identity.audit((String) admin.get("username"), "MODERATE_REVIEW", "REVIEW", review, "可见：" + visible);
    }
}