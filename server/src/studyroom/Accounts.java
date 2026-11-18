package studyroom;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import static studyroom.BusinessException.require;

@Service
public class Accounts {
    static final String PUBLIC_FIELDS = "id,username,display_name,role,enabled,blacklist_until,created_at";

    private final Store db;
    private final Clock clock;

    public Accounts(Store db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Map<String,Object> current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        require(auth != null && auth.isAuthenticated() && !auth.getName().equals("anonymousUser"),
            401, "请先登录");
        var user = db.one("SELECT " + PUBLIC_FIELDS + " FROM app_users WHERE username=?", auth.getName());
        require(Store.flag(user, "enabled"), 403, "账号已停用，请联系管理员");
        return user;
    }

    public Map<String,Object> admin() {
        var user = current();
        require("ADMIN".equals(user.get("role")), 403, "仅管理员可操作");
        return user;
    }

    public void audit(String actor, String action, String type, Long id, String detail) {
        db.update("INSERT INTO audit_logs(actor,action,target_type,target_id,detail,created_at) VALUES(?,?,?,?,?,?)",
            actor, action, type, id, detail, LocalDateTime.now(clock));
    }
}
