package studyroom;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

/** 请求 DTO：字段上限与数据库列长度一致，防止静默截断。 */
public final class Requests {
    private Requests() {}

    public record Login(@NotBlank String username, @NotBlank String password) {}

    public record Room(@NotBlank @Size(max=60) String name, @NotBlank @Size(max=80) String building,
        @Min(-5) @Max(150) int floorNo, @NotBlank @Pattern(regexp="([01]\\d|2[0-3]):[0-5]\\d") String openTime,
        @NotBlank @Pattern(regexp="([01]\\d|2[0-3]):[0-5]\\d") String closeTime,
        @NotNull @Size(max=500) String description, boolean enabled, boolean isQuiet) {}

    public record Seat(@Positive long roomId, @NotBlank @Size(max=20) String code,
        boolean hasPower, boolean enabled) {}

    public record Booking(@Positive long seatId, @NotNull LocalDateTime startAt, @NotNull LocalDateTime endAt) {}

    /** 管理员对预约执行动作时的统一理由：确认待核查、撤销待核查、撤销违约都用它。 */
    public record Reason(@NotBlank @Size(max=300) String reason) {}

    /** 手动登记违约：按 V-01 使用“违约次数”，不再使用积分。 */
    public record Violation(@Positive long reservationId, @NotBlank @Size(max=300) String reason) {}

    public record Complaint(Long reservationId, @NotBlank @Size(max=80) String topic,
        @NotBlank @Size(max=2000) String content) {}

    /** expectedStatus 防止两个管理员同时处理同一投诉；不一致时返回 409。 */
    public record Reply(@NotBlank @Pattern(regexp="PENDING|PROCESSING|RESOLVED") String status,
        @NotBlank @Size(max=2000) String reply, @NotBlank String expectedStatus) {}

    public record Review(@Positive long reservationId, @Min(1) @Max(5) int rating,
        @NotBlank @Size(max=1000) String content) {}

    public record Visibility(boolean visible) {}

    /** 管理员新增学生账号，替代原来的学生自助注册（报告未把注册列为 FR）。 */
    public record UserCreate(@NotBlank @Pattern(regexp="[a-zA-Z0-9_]{4,32}") String username,
        @NotBlank @Size(max=40) String displayName, @NotBlank @Size(min=8,max=72) String password) {}

    public record UserUpdate(@NotBlank @Size(max=40) String displayName, boolean enabled) {}

    public record Blacklist(@Min(1) @Max(365) int days, @NotBlank @Size(max=300) String reason) {}
}
