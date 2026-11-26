package studyroom.platform;

import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Single read entry for {@code reservation_policy}.
 *
 * <p>
 * Temporary note: {@code BookingService.rawPolicy()} still reads the table
 * directly until S6 moves it here; this class exists so the credit slice never
 * grows a second policy read.
 */
@Service
public class PolicyService {
    private final Store db;

    public PolicyService(Store db) {
        this.db = db;
    }

    public Map<String, Object> current() {
        return db.one("SELECT * FROM reservation_policy WHERE id=1");
    }
}