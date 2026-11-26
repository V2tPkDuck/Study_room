package studyroom.credit;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import studyroom.Requests;

/**
 * Credit routes: own/all violations, revoke, and administrator blacklist
 * management.
 *
 * <p>
 * Note: {@code POST /admin/violations} deliberately stays in BookingApi — it
 * changes a reservation's state, while credit only keeps the account.
 */
@RestController
@RequestMapping("/api")
public class CreditApi {
    private final CreditService credit;

    public CreditApi(CreditService credit) {
        this.credit = credit;
    }

    /** FR-08: the caller's own violations. */
    @GetMapping("/violations")
    List<Map<String, Object>> myViolations() {
        return credit.violations(false);
    }

    /** FR-17: every violation record. */
    @GetMapping("/admin/violations")
    List<Map<String, Object>> allViolations() {
        return credit.violations(true);
    }

    /** FR-17: revoke a violation record. */
    @PostMapping("/admin/violations/{id}/revoke")
    Map<String, String> revokeViolation(@PathVariable long id, @Valid @RequestBody Requests.Reason input) {
        credit.revoke(id, input.reason());
        return ok();
    }

    /** FR-19: add a blacklist record with its own reason and end date. */
    @PostMapping("/admin/users/{id}/blacklist")
    Map<String, Long> addBlacklist(@PathVariable long id, @Valid @RequestBody Requests.Blacklist input) {
        return Map.of("id", credit.manualBlacklist(id, input));
    }

    /** FR-19: release every active blacklist record of one student. */
    @PostMapping("/admin/users/{id}/blacklist/release")
    Map<String, String> releaseBlacklist(@PathVariable long id, @Valid @RequestBody Requests.Reason input) {
        credit.release(id, input.reason());
        return ok();
    }

    /** FR-19: blacklist history of one student. */
    @GetMapping("/admin/users/{id}/blacklist")
    List<Map<String, Object>> blacklistHistory(@PathVariable long id) {
        return credit.records(id);
    }

    private Map<String, String> ok() {
        return Map.of("message", "操作成功");
    }
}