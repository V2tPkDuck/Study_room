package studyroom.platform;

import java.time.Clock;
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
    private final Clock clock;

    public IdentityService(Store db, Clock clock) {
        this.db = db;
        this.clock = clock;
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

    /** Appends one audit row; actor is a username or the literal SYSTEM. */
    public void audit(String actor, String action, String type, Long id, String detail) {
        db.update("INSERT INTO audit_logs(actor,action,target_type,target_id,detail,created_at) VALUES(?,?,?,?,?,?)",
            actor, action, type, id, detail, LocalDateTime.now(clock));
    }
    
    public void setBlacklistUntil(long userId, LocalDateTime end) {
        db.update("UPDATE app_users SET blacklist_until=? WHERE id=?", end, userId);
    }
}
