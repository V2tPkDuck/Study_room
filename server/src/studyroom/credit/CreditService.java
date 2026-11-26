package studyroom.credit;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studyroom.Requests;
import studyroom.platform.IdentityService;
import studyroom.platform.PolicyService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;
import static studyroom.platform.Store.flag;
import static studyroom.platform.Store.id;

/**
 * Credit and governance: violation records, the automatic blacklist threshold,
 * and administrator-issued blacklist records.
 *
 * <p>
 * Dependency rule: booking may call credit, never the reverse. The reservation
 * state change stays in BookingService; this class only keeps the account.
 */
@Service
public class CreditService {
    private final Store db;
    private final CreditRepository credit;
    private final IdentityService identity;
    private final PolicyService policy;
    private final Clock clock;

    public CreditService(Store db, CreditRepository credit, IdentityService identity,
            PolicyService policy, Clock clock) {
        this.db = db;
        this.credit = credit;
        this.identity = identity;
        this.policy = policy;
        this.clock = clock;
    }

    /** Deadline used by booking creation; {@code null} means no restriction. */
    public LocalDateTime blacklistUntil(long userId) {
        return credit.blacklistUntil(userId);
    }

    /** FR-08: own violations; FR-17: every record for the administrator. */
    public List<Map<String, Object>> violations(boolean admin) {
        var user = admin ? identity.admin() : identity.current();
        return credit.violations(admin, id(user, "id"));
    }

    /** FR-17: guard for manual registration; the UNIQUE key is the backstop. */
    public boolean hasViolation(long reservationId) {
        return credit.countViolationsOfReservation(reservationId) > 0;
    }

    /**
     * Records one violation and applies the automatic blacklist threshold.
     *
     * <p>
     * Must run inside the caller's transaction (default REQUIRED): if the
     * reservation update fails, the violation must roll back with it. Never
     * {@code REQUIRES_NEW}.
     */
    @Transactional
    public void record(long userId, long reservationId, String source, String reason, String actor) {
        var now = LocalDateTime.now(clock);
        long violation = credit.insertViolation(userId, reservationId, source, reason, actor, now);
        identity.audit(actor, "VIOLATION", "VIOLATION", violation, reason);

        var p = policy.current();
        long count = credit.countActiveViolations(userId);
        if (count >= id(p, "blacklist_threshold")) {
            applyBlacklist(userId, "有效违约达到 " + count + " 次", (int) id(p, "blacklist_days"), "SYSTEM");
        }
    }

    /**
     * FR-17: revoke one violation. The automatic blacklist is a separate decision
     * and is not lifted.
     */
    @Transactional
    public void revoke(long violationId, String reason) {
        db.lock();
        var admin = identity.admin();
        var v = credit.violation(violationId);
        require(!flag(v, "revoked"), 409, "记录已撤销");
        credit.revokeViolation(violationId, reason);
        identity.audit((String) admin.get("username"), "REVOKE_VIOLATION", "VIOLATION", violationId, reason);
    }

    /**
     * FR-19: administrator adds a blacklist record with its own reason and date
     * range.
     */
    @Transactional
    public long manualBlacklist(long userId, Requests.Blacklist input) {
        db.lock();
        var admin = identity.admin();
        credit.requireUser(userId);
        var now = LocalDateTime.now(clock);
        var end = now.plusDays(input.days());
        long record = credit.insertBlacklist(userId, input.reason(), now, end, (String) admin.get("username"));
        extendBlacklistUntil(userId, end);
        identity.audit((String) admin.get("username"), "BLACKLIST", "BLACKLIST", record, input.reason());
        return record;
    }

    /**
     * FR-19: releases every active record and recomputes the deadline on the
     * account.
     */
    @Transactional
    public void release(long userId, String reason) {
        db.lock();
        var admin = identity.admin();
        credit.requireUser(userId);
        require(credit.countActiveBlacklist(userId) > 0, 409, "该学生当前没有有效黑名单");
        var now = LocalDateTime.now(clock);
        credit.releaseActiveBlacklist(userId, now, (String) admin.get("username"), reason);
        identity.setBlacklistUntil(userId, credit.latestActiveBlacklistEnd(userId, now));
        identity.audit((String) admin.get("username"), "RELEASE_BLACKLIST", "BLACKLIST", userId, reason);
    }

    /** FR-19: blacklist history of one student. */
    public List<Map<String, Object>> records(long userId) {
        identity.admin();
        return credit.blacklistRecords(userId);
    }

    /**
     * Automatic blacklist: reuse an active record, so a repeated sweep or retry
     * never extends the end date.
     */
    private void applyBlacklist(long userId, String reason, int days, String actor) {
        var now = LocalDateTime.now(clock);
        if (credit.hasActiveBlacklist(userId, now))
            return;

        var end = now.plusDays(days);
        long record = credit.insertBlacklist(userId, reason, now, end, actor);
        extendBlacklistUntil(userId, end);
        identity.audit(actor, "BLACKLIST", "BLACKLIST", record, reason);
    }

    /** {@code app_users.blacklist_until} is written only by IdentityService. */
    private void extendBlacklistUntil(long userId, LocalDateTime end) {
        var current = credit.blacklistUntil(userId);
        if (current == null || current.isBefore(end)) {
            identity.setBlacklistUntil(userId, end);
        }
    }
}