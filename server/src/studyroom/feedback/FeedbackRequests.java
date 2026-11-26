package studyroom.feedback;

import jakarta.validation.constraints.*;

/** Feedback-slice request payloads. */
public final class FeedbackRequests {
    private FeedbackRequests() {
    }

    public record Complaint(Long reservationId, @NotBlank @Size(max = 80) String topic,
            @NotBlank @Size(max = 2000) String content) {
    }

    /**
     * expectedStatus stops two administrators from overwriting each other; a
     * mismatch returns 409.
     */
    public record Reply(@NotBlank @Pattern(regexp = "PENDING|PROCESSING|RESOLVED") String status,
            @NotBlank @Size(max = 2000) String reply, @NotBlank String expectedStatus) {
    }

    public record Review(@Positive long reservationId, @Min(1) @Max(5) int rating,
            @NotBlank @Size(max = 1000) String content) {
    }

    public record Visibility(boolean visible) {
    }
}