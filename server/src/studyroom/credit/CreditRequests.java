package studyroom.credit;

import jakarta.validation.constraints.*;

/** Credit-slice request payloads. */
public final class CreditRequests {
    private CreditRequests() {
    }

    /** Shared reason payload for violation revoke and blacklist release. */
    public record Reason(@NotBlank @Size(max = 300) String reason) {
    }

    public record Blacklist(@Min(1) @Max(365) int days, @NotBlank @Size(max = 300) String reason) {
    }
}