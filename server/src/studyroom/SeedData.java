package studyroom;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 首次启动时补齐策略、演示账号和 72 个座位；不重置已有数据。 */
@Component
public class SeedData implements ApplicationRunner {
    private final Store db;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final boolean demo;
    private final String adminPassword;
    private final String studentPassword;

    public SeedData(Store db, PasswordEncoder passwords, Clock clock,
                    @Value("${app.seed}") boolean demo,
                    @Value("${app.admin-password}") String adminPassword,
                    @Value("${app.student-password}") String studentPassword) {
        this.db = db;
        this.passwords = passwords;
        this.clock = clock;
        this.demo = demo;
        this.adminPassword = adminPassword;
        this.studentPassword = studentPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (db.count("SELECT COUNT(*) FROM booking_mutex") == 0) {
            db.update("INSERT INTO booking_mutex(id) VALUES(1)");
        }
        db.lock();
        if (db.count("SELECT COUNT(*) FROM reservation_policy") == 0) {
            // max_days, max_hours, check_in_before, grace_minutes, max_active, threshold, days
            db.update("INSERT INTO reservation_policy VALUES(1,7,4,15,15,3,2,7)");
        }

        var now = LocalDateTime.now(clock);
        if (db.count("SELECT COUNT(*) FROM app_users WHERE username='admin'") == 0) {
            db.update("INSERT INTO app_users(username,display_name,password_hash,role,enabled,created_at) "
                + "VALUES('admin','系统管理员',?,'ADMIN',TRUE,?)", passwords.encode(adminPassword), now);
        }
        if (!demo) return;

        if (db.count("SELECT COUNT(*) FROM app_users WHERE username='student'") == 0) {
            db.update("INSERT INTO app_users(username,display_name,password_hash,role,enabled,created_at) "
                + "VALUES('student','演示同学一',?,'STUDENT',TRUE,?)", passwords.encode(studentPassword), now);
        }
        if (db.count("SELECT COUNT(*) FROM app_users WHERE username='student2'") == 0) {
            db.update("INSERT INTO app_users(username,display_name,password_hash,role,enabled,created_at) "
                + "VALUES('student2','演示同学二',?,'STUDENT',TRUE,?)", passwords.encode(studentPassword), now);
        }

        if (db.count("SELECT COUNT(*) FROM rooms") == 0) {
            for (int n = 1; n <= 3; n++) {
                long room = db.insert("INSERT INTO rooms(name,building,floor_no,open_time,close_time,description,enabled,is_quiet) "
                    + "VALUES(?,?,?,'07:00','23:00',?,TRUE,?)",
                    new String[]{"静思阅览室", "晨光自习室", "研习讨论室"}[n - 1], "图书馆", n + 1,
                    "请保持安静，离开时带走个人物品。", n != 3);
                for (int s = 1; s <= 24; s++) {
                    db.update("INSERT INTO seats(room_id,code,has_power,enabled) VALUES(?,?,?,TRUE)",
                        room, String.format("%02d", s), s % 3 != 0);
                }
            }
        }
    }
}
