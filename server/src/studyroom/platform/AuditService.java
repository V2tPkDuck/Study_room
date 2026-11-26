package studyroom.platform;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;

/** Sole writer and reader of audit_logs. */
@Service
public class AuditService {
    private final Store db;
    private final Clock clock;

    public AuditService(Store db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void audit(String actor, String action, String type, Long id, String detail) {
        db.update("INSERT INTO audit_logs(actor,action,target_type,target_id,detail,created_at) VALUES(?,?,?,?,?,?)",
                actor, action, type, id, detail, LocalDateTime.now(clock));
    }

    public List<Map<String, Object>> recent() {
        return db.list("SELECT * FROM audit_logs ORDER BY id DESC LIMIT 500");
    }
}