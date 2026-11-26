package studyroom.credit;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Repository;
import studyroom.platform.Store;

/**
 * Sole writer of {@code violations} and {@code blacklist_records}.
 *
 * <p>Reading {@code app_users} is allowed here; writing
 * {@code app_users.blacklist_until} goes through {@link studyroom.platform.IdentityService}.
 */
@Repository
public class CreditRepository {
    private final Store db;

    public CreditRepository(Store db) {
        this.db = db;
    }

    // ---------- violations ----------

    long insertViolation(long userId, long reservationId, String source, String reason,
                         String actor, LocalDateTime now) {
        return db.insert("INSERT INTO violations(user_id,reservation_id,source,reason,revoked,created_by,created_at) "
            + "VALUES(?,?,?,?,FALSE,?,?)", userId, reservationId, source, reason, actor, now);
    }

    long countActiveViolations(long userId) {
        return db.count("SELECT COUNT(*) FROM violations WHERE user_id=? AND revoked=FALSE", userId);
    }

    long countViolationsOfReservation(long reservationId) {
        return db.count("SELECT COUNT(*) FROM violations WHERE reservation_id=?", reservationId);
    }

    Map<String,Object> violation(long violationId) {
        return db.one("SELECT * FROM violations WHERE id=?", violationId);
    }

    void revokeViolation(long violationId, String reason) {
        db.update("UPDATE violations SET revoked=TRUE,revoke_reason=? WHERE id=?", reason, violationId);
    }

    List<Map<String,Object>> violations(boolean admin, long userId) {
        return db.list("SELECT v.*,u.username,u.display_name FROM violations v "
            + "JOIN app_users u ON v.user_id=u.id "
            + (admin ? "" : "WHERE v.user_id=? ")
            + "ORDER BY v.id DESC LIMIT 500", admin ? new Object[]{} : new Object[]{userId});
    }

    // ---------- blacklist ----------

    boolean hasActiveBlacklist(long userId, LocalDateTime now) {
        return db.count("SELECT COUNT(*) FROM blacklist_records "
            + "WHERE user_id=? AND active=TRUE AND end_at>?", userId, now) > 0;
    }

    long insertBlacklist(long userId, String reason, LocalDateTime start, LocalDateTime end, String actor) {
        return db.insert("INSERT INTO blacklist_records(user_id,reason,start_at,end_at,created_by,active) "
            + "VALUES(?,?,?,?,?,TRUE)", userId, reason, start, end, actor);
    }

    long countActiveBlacklist(long userId) {
        return db.count("SELECT COUNT(*) FROM blacklist_records WHERE user_id=? AND active=TRUE", userId);
    }

    void releaseActiveBlacklist(long userId, LocalDateTime now, String actor, String reason) {
        db.update("UPDATE blacklist_records SET active=FALSE,released_at=?,released_by=?,release_reason=? "
            + "WHERE user_id=? AND active=TRUE", now, actor, reason, userId);
    }

    LocalDateTime latestActiveBlacklistEnd(long userId, LocalDateTime now) {
        var row = db.one("SELECT MAX(end_at) AS max_end FROM blacklist_records "
            + "WHERE user_id=? AND active=TRUE AND end_at>?", userId, now);
        return (LocalDateTime) row.get("max_end");
    }

    List<Map<String,Object>> blacklistRecords(long userId) {
        return db.list("SELECT * FROM blacklist_records WHERE user_id=? ORDER BY id DESC LIMIT 200", userId);
    }

    // ---------- app_users reads used by this domain ----------

    void requireUser(long userId) {
        db.one("SELECT id FROM app_users WHERE id=?", userId);
    }

    LocalDateTime blacklistUntil(long userId) {
        var user = db.one("SELECT blacklist_until FROM app_users WHERE id=?", userId);
        return (LocalDateTime) user.get("blacklist_until");
    }
}