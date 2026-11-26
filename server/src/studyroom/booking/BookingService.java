package studyroom.booking;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studyroom.Requests;
import studyroom.credit.CreditService;
import studyroom.platform.BusinessException;
import studyroom.platform.IdentityService;
import studyroom.platform.PolicyService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;
import static studyroom.platform.Store.*;

/** Reservation lifecycle: create, list and the student state machine. */
@Service
public class BookingService {
    private static final Set<String> STATUSES = Set.of(
            "BOOKED", "PENDING", "CHECKED_IN", "COMPLETED", "CANCELLED", "VIOLATED");

    private final Store db;
    private final IdentityService identity;
    private final Clock clock;
    private final CreditService credit;
    private final PolicyService policyService;
    private final SeatQueryService seatQuery;
    private final ReservationRepository reservations;

    public BookingService(Store db, IdentityService identity, Clock clock, CreditService credit,
            PolicyService policyService, SeatQueryService seatQuery,
            ReservationRepository reservations) {
        this.db = db;
        this.identity = identity;
        this.clock = clock;
        this.credit = credit;
        this.policyService = policyService;
        this.seatQuery = seatQuery;
        this.reservations = reservations;
    }

    @Transactional
    public Map<String, Object> create(Requests.Booking input) {
        db.lock();
        var user = identity.current();
        var now = LocalDateTime.now(clock);
        var p = policyService.current();

        var black = credit.blacklistUntil(id(user, "id"));
        require(black == null || !black.isAfter(now), 403, "账号处于黑名单限制期，截止 " + black);

        var seat = seatQuery.seatContext(input.seatId());
        require(flag(seat, "enabled") && flag(seat, "room_enabled"), 409, "座位或自习室已停用");

        var start = input.startAt();
        var end = input.endAt();
        require(start.isBefore(end), 400, "结束时间应晚于开始时间");
        require(!start.isBefore(now), 400, "不能预约过去的时间");
        require(start.toLocalDate().equals(end.toLocalDate()), 400, "预约不能跨天");
        require(!start.toLocalDate().isAfter(now.toLocalDate().plusDays(id(p, "max_days"))),
                400, "超出可预约日期范围");
        long seconds = Duration.between(start, end).getSeconds();
        require(seconds >= 900 && seconds <= id(p, "max_hours") * 3600,
                400, "预约时长至少 15 分钟，且不能超过最大时长");
        require(!start.toLocalTime().isBefore(LocalTime.parse((String) seat.get("open_time")))
                && !end.toLocalTime().isAfter(LocalTime.parse((String) seat.get("close_time"))),
                400, "预约时间不在自习室开放时间内");

        require(reservations.countActiveOfUser(id(user, "id")) < id(p, "max_active"),
                409, "每人最多同时保留 " + id(p, "max_active") + " 条待使用预约");
        require(reservations.countSeatConflicts(input.seatId(), start, end) == 0,
                409, "该座位在所选时段已被预约");
        require(reservations.countUserConflicts(id(user, "id"), start, end) == 0,
                409, "你已有重叠时段的预约");

        long booking = reservations.insert(id(user, "id"), input.seatId(), start, end, now);
        identity.audit((String) user.get("username"), "BOOK", "RESERVATION", booking,
                "创建预约：" + start + " ~ " + end);
        return reservations.view(booking);
    }

    public Map<String, Object> reservations(boolean admin, String status, LocalDate date, int page, int size) {
        var user = admin ? identity.admin() : identity.current();
        require(page >= 1 && size >= 1 && size <= 100, 400, "页码至少 1，每页 1-100 条");
        if (status != null && !status.isBlank()) {
            require(STATUSES.contains(status), 400, "预约状态无效");
        }
        return reservations.page(admin, id(user, "id"), status, date, page, size);
    }

    /**
     * Student actions only: cancel, check-in, check-out. Admin proxy actions are
     * out of scope (SRS 4.2).
     */
    @Transactional
    public void transition(long booking, String action) {
        db.lock();
        var user = identity.current();
        var b = reservations.byId(booking);
        require(id(user, "id") == id(b, "user_id"), 403, "不能操作他人的预约");

        var now = LocalDateTime.now(clock);
        var start = time(b, "start_at");
        var end = time(b, "end_at");
        var p = policyService.current();
        String status = (String) b.get("status");

        switch (action) {
            case "cancel" -> {
                require("BOOKED".equals(status), 409, "仅“已预约”的预约可以取消");
                require(now.isBefore(start), 409, "预约已开始，不能取消");
                reservations.cancel(booking, "学生取消");
            }
            case "check-in" -> {
                require("BOOKED".equals(status), 409, "当前状态不能签到");
                require(!now.isBefore(start.minusMinutes(id(p, "check_in_before")))
                        && now.isBefore(start.plusMinutes(id(p, "grace_minutes")))
                        && now.isBefore(end),
                        409, "当前不在签到时间窗口内");
                reservations.checkIn(booking, now);
            }
            case "check-out" -> {
                require("CHECKED_IN".equals(status), 409, "只有“已签到”的预约可以签退");
                reservations.checkOut(booking, now.isBefore(end) ? now : end);
            }
            default -> throw new BusinessException(400, "未知操作");
        }
        identity.audit((String) user.get("username"), action.toUpperCase(Locale.ROOT),
                "RESERVATION", booking, "状态从 " + status + " 流转");
    }
}