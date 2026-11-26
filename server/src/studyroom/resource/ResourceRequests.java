package studyroom.resource;

import jakarta.validation.constraints.*;

/** Resource-slice request payloads. */
public final class ResourceRequests {
    private ResourceRequests() {
    }

    public record Room(@NotBlank @Size(max = 60) String name, @NotBlank @Size(max = 80) String building,
            @Min(-5) @Max(150) int floorNo,
            @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String openTime,
            @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String closeTime,
            @NotNull @Size(max = 500) String description, boolean enabled, boolean isQuiet) {
    }

    public record Seat(@Positive long roomId, @NotBlank @Size(max = 20) String code,
            boolean hasPower, boolean enabled) {
    }

    /** Administrator creates a student account; self-registration is not an FR. */
    public record UserCreate(@NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{4,32}") String username,
            @NotBlank @Size(max = 40) String displayName, @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record UserUpdate(@NotBlank @Size(max = 40) String displayName, boolean enabled) {
    }
}