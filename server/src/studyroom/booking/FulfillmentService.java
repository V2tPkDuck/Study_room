package studyroom.booking;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studyroom.credit.CreditService;
import studyroom.platform.IdentityService;
import studyroom.platform.PolicyService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;
import static studyroom.platform.Store.id;

/** FR-16/17: sweep, pending review and manual violation registration. */
@Service
public class FulfillmentService {
    private final Store db;
    private final IdentityService identity;
    private final Clock clock;
    private final CreditService credit;
    private final PolicyService policyService;
    private final ReservationRepository reservations;

    public FulfillmentService(Store db, IdentityService identity, Clock clock, CreditService credit,
            PolicyService policyService, ReservationRepository reservations) {
        this.db = db;
        this.identity = identity;
        this.clock = clock;
        this.credit = credit;
        this.policyService = policyService;
        this.reservations = reservations;
    }

    /**
     * Auto-fulfilment: a missed check-in becomes PENDING for manual review, never a
     * direct violation (report V-01). An expired checked-in reservation is
     * completed.
     * The task is idempotent: running it twice processes nothing twice.
     */
    @Transactional
    public int sweep() {
        db.lock();
        var now = LocalDateTime.now(clock);
        var p = policyService.current();
        int count = 0;

        for (var b : reservations.noShowCandidates(now.minusMinutes(id(p, "grace_minutes")), now)) {
            reservations.toPending(id(b, "id"));
            identity.audit("SYSTEM", "PENDING_NO_CHECK_IN", "RESERVATION", id(b, "id"),
                    "超时未签到，转待核查");
            count++;
        }
        for (var b : reservations.expiredCheckIns(now)) {
            reservations.autoComplete(id(b, "id"));
            identity.audit("SYSTEM", "AUTO_CHECK_OUT", "RESERVATION", id(b, "id"),
                    "预约到期自动签退");
            count++;
        }
        return count;
    }

    /**
     * Administrator closes a PENDING reservation: confirm the violation, or dismiss
     * and release it.
     */
    @Transactional
    public void resolvePending(long booking, boolean violation, String reason) {
        db.lock();
        var admin = identity.admin();
        var b = reservations.byId(booking);
        require("PENDING".equals(b.get("status")), 409, "该预约不在待核查状态");

        if (violation) {
            reservations.toViolated(booking, reason);
            credit.record(id(b, "user_id"), booking, "AUTO", reason, (String) admin.get("username"));
            identity.audit((String) admin.get("username"), "CONFIRM_NO_CHECK_IN", "RESERVATION", booking, reason);
        } else {
            reservations.toCancelled(booking, reason);
            identity.audit((String) admin.get("username"), "DISMISS_PENDING", "RESERVATION", booking, reason);
        }
    }

    /**
     * Administrator records a violation manually; one reservation can own at most
     * one violation.
     */
    @Transactional
    public void manualViolation(BookingRequests.Violation input) {
        db.lock();
        var admin = identity.admin();
        var b = reservations.byId(input.reservationId());
        require(Set.of("BOOKED", "CHECKED_IN", "PENDING").contains(b.get("status")),
                409, "只有未结束的预约可以登记违约");
        require(!credit.hasViolation(input.reservationId()), 409, "该预约已有违约记录");

        reservations.toViolatedManually(input.reservationId(), LocalDateTime.now(clock), input.reason());
        credit.record(id(b, "user_id"), input.reservationId(), "MANUAL", input.reason(),
                (String) admin.get("username"));
        identity.audit((String) admin.get("username"), "MANUAL_VIOLATION", "RESERVATION",
                input.reservationId(), input.reason());
    }
}