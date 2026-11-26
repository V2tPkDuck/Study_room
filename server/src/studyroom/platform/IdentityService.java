package studyroom.platform;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import static studyroom.platform.BusinessException.require;

/**
 * Identity gate shared by every business slice.
 *
 * <p>It answers two questions for a use case: who is calling, and is that caller
 * an enabled student or an administrator. It is also the single place that
 * writes audit_logs, so a controller cannot create an audit trail on its own.
 */
@Service
public class IdentityService {
    /** Columns safe to return to a client; password_hash is never included. */
    public static final String PUBLIC_FIELDS = "id,username,display_name,role,enabled,blacklist_until,created_at";

    private final Store db;
    private final AuditService audit;

    public IdentityService(Store db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /** Returns the enabled account behind the current session, or throws 401/403. */
    public Map<String,Object> current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        require(auth != null && auth.isAuthenticated() && !auth.getName().equals("anonymousUser"),
            401, "请先登录");
        var user = db.one("SELECT " + PUBLIC_FIELDS + " FROM app_users WHERE username=?", auth.getName());
        require(Store.flag(user, "enabled"), 403, "账号已停用，请联系管理员");
        return user;
    }

    /** Same as {@link #current()} but also requires the ADMIN role. */
    public Map<String,Object> admin() {
        var user = current();
        require("ADMIN".equals(user.get("role")), 403, "仅管理员可操作");
        return user;
    }

    /** Backwards-compatible entry point; the SQL lives in {@link AuditService}. */
    public void audit(String actor, String action, String type, Long id, String detail) {
        audit.audit(actor, action, type, id, detail);
    }
    
    public void setBlacklistUntil(long userId, LocalDateTime end) {
        db.update("UPDATE app_users SET blacklist_until=? WHERE id=?", end, userId);
    }

    /**
     * Read-only lookup used by the user-admin slice; keeps the original 404
     * semantics.
     */
    public Map<String, Object> user(long userId) {
        return db.one("SELECT " + PUBLIC_FIELDS + " FROM app_users WHERE id=?", userId);
    }

    public boolean usernameExists(String username) {
        return db.count("SELECT COUNT(*) FROM app_users WHERE username=?", username) > 0;
    }

    /**
     * FR-18: creates a student account; password hashing stays in the calling use
     * case.
     */
    public long createStudent(String username, String displayName, String passwordHash, LocalDateTime now) {
        return db.insert("INSERT INTO app_users(username,display_name,password_hash,role,enabled,created_at) "
                + "VALUES(?,?,?,'STUDENT',TRUE,?)", username, displayName, passwordHash, now);
    }

    /** FR-18: rename or enable/disable an account. */
    public void updateProfile(long userId, String displayName, boolean enabled) {
        db.update("UPDATE app_users SET display_name=?,enabled=? WHERE id=?", displayName, enabled, userId);
    }
}
