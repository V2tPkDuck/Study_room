package studyroom.platform;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AdminAuditController {
    private final IdentityService identity;
    private final AuditService audit;

    public AdminAuditController(IdentityService identity, AuditService audit) {
        this.identity = identity;
        this.audit = audit;
    }

    /** QR-SEC-04: the latest audit rows. */
    @GetMapping("/admin/audits")
    List<Map<String, Object>> audits() {
        identity.admin();
        return audit.recent();
    }
}