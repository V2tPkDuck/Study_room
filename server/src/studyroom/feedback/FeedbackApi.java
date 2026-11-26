package studyroom.feedback;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Complaint and review endpoints.
 *
 * <p>Responsibilities: students submit and withdraw complaints, submit reviews and
 * read public reviews; administrators list, reply to and moderate them. Ownership
 * and status checks stay in {@link FeedbackService}.
 */
@RestController
@RequestMapping("/api")
public class FeedbackApi {
    private final ComplaintService complaints;
    private final ReviewService reviews;

    public FeedbackApi(ComplaintService complaints, ReviewService reviews) {
        this.complaints = complaints;
        this.reviews = reviews;
    }

    /** FR-11: the caller's own complaints. */
    @GetMapping("/complaints")
    List<Map<String,Object>> myComplaints() {
        return complaints.complaints(false);
    }

    /** FR-10: submit a complaint, optionally linked to one of the caller's reservations. */
    @PostMapping("/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Long> createComplaint(@Valid @RequestBody FeedbackRequests.Complaint input) {
        return Map.of("id", complaints.complain(input));
    }

    /** FR-10: withdraw a complaint while it is still PENDING. */
    @PostMapping("/complaints/{id}/withdraw")
    Map<String,String> withdrawComplaint(@PathVariable long id) {
        complaints.withdraw(id);
        return ok();
    }

    /** FR-09/FR-20: public reviews visible to students. */
    @GetMapping("/reviews")
    List<Map<String,Object>> publicReviews() {
        return reviews.reviews(false);
    }

    /** FR-09: submit one review for a completed reservation. */
    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String,Long> createReview(@Valid @RequestBody FeedbackRequests.Review input) {
        return Map.of("id", reviews.review(input));
    }

    /** FR-11/FR-21: every complaint for the administrator console. */
    @GetMapping("/admin/complaints")
    List<Map<String,Object>> allComplaints() {
        return complaints.complaints(true);
    }

    /** FR-21/FR-22: reply with an expectedStatus guard against concurrent handling. */
    @PutMapping("/admin/complaints/{id}")
    Map<String,String> replyComplaint(@PathVariable long id, @Valid @RequestBody FeedbackRequests.Reply input) {
        complaints.reply(id, input);
        return ok();
    }

    /** FR-20: every review, including hidden ones. */
    @GetMapping("/admin/reviews")
    List<Map<String,Object>> allReviews() {
        return reviews.reviews(true);
    }

    /** FR-20: show or hide a review. */
    @PutMapping("/admin/reviews/{id}")
    Map<String,String> moderateReview(@PathVariable long id, @RequestBody FeedbackRequests.Visibility input) {
        reviews.visibility(id, input.visible());
        return ok();
    }

    private Map<String,String> ok() {
        return Map.of("message", "操作成功");
    }
}
