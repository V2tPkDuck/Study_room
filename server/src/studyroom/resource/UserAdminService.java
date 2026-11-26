package studyroom.resource;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studyroom.Requests;
import studyroom.platform.IdentityService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;
import static studyroom.platform.Store.id;

/** FR-18: user search, creation, profile update and enable/disable. */
@Service
public class UserAdminService {
    private final Store db;
    private final IdentityService identity;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public UserAdminService(Store db, IdentityService identity, PasswordEncoder passwords, Clock clock) {
        this.db = db;
        this.identity = identity;
        this.passwords = passwords;
        this.clock = clock;
    }

    public List<Map<String, Object>> users(String query) {
        identity.admin();
        String q = query == null ? "" : query;
        return db.list("SELECT " + IdentityService.PUBLIC_FIELDS + " FROM app_users "
                + "WHERE username LIKE ? OR display_name LIKE ? ORDER BY id DESC LIMIT 500",
                "%" + q + "%", "%" + q + "%");
    }

    @Transactional
    public long createUser(Requests.UserCreate input) {
        db.lock();
        var admin = identity.admin();
        require(!identity.usernameExists(input.username()), 409, "账号已存在");
        var now = LocalDateTime.now(clock);
        long userId = identity.createStudent(input.username(), input.displayName().trim(),
                passwords.encode(input.password()), now);
        identity.audit((String) admin.get("username"), "CREATE_USER", "USER", userId, input.username());
        return userId;
    }

    @Transactional
    public void updateUser(long userId, Requests.UserUpdate input) {
        db.lock();
        var admin = identity.admin();
        identity.user(userId);
        require(id(admin, "id") != userId || input.enabled(), 400, "不能停用当前登录账号");
        identity.updateProfile(userId, input.displayName().trim(), input.enabled());
        identity.audit((String) admin.get("username"), "UPDATE_USER", "USER", userId,
                "修改资料/启停账号：" + input.enabled());
    }
}