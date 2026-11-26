package studyroom.resource;

import java.util.*;
import org.springframework.stereotype.Repository;
import studyroom.platform.Store;

/**
 * Sole writer of rooms and seats. Reads of reservations are guards only (S6
 * will ask booking).
 */
@Repository
public class ResourceRepository {
    private final Store db;

    public ResourceRepository(Store db) {
        this.db = db;
    }

    // ---------- guards ----------

    Map<String, Object> requireRoom(long roomId) {
        return db.one("SELECT id FROM rooms WHERE id=?", roomId);
    }

    Map<String, Object> requireSeat(long seatId) {
        return db.one("SELECT * FROM seats WHERE id=?", seatId);
    }

    long countSeats(long roomId) {
        return db.count("SELECT COUNT(*) FROM seats WHERE room_id=?", roomId);
    }

    long countActiveReservationsOfRoom(long roomId) {
        return db.count("SELECT COUNT(*) FROM reservations b JOIN seats s ON b.seat_id=s.id "
                + "WHERE s.room_id=? AND b.status IN ('BOOKED','CHECKED_IN')", roomId);
    }

    long countActiveReservationsOfSeat(long seatId) {
        return db.count("SELECT COUNT(*) FROM reservations WHERE seat_id=? AND status IN ('BOOKED','CHECKED_IN')",
                seatId);
    }

    long countReservationsOfSeat(long seatId) {
        return db.count("SELECT COUNT(*) FROM reservations WHERE seat_id=?", seatId);
    }

    // ---------- rooms ----------

    long insertRoom(ResourceRequests.Room input) {
        return db.insert("INSERT INTO rooms(name,building,floor_no,open_time,close_time,description,enabled,is_quiet) "
                + "VALUES(?,?,?,?,?,?,?,?)", input.name().trim(), input.building().trim(), input.floorNo(),
                input.openTime(), input.closeTime(), input.description(), input.enabled(), input.isQuiet());
    }

    void updateRoom(long roomId, ResourceRequests.Room input) {
        db.update(
                "UPDATE rooms SET name=?,building=?,floor_no=?,open_time=?,close_time=?,description=?,enabled=?,is_quiet=? "
                        + "WHERE id=?",
                input.name().trim(), input.building().trim(), input.floorNo(),
                input.openTime(), input.closeTime(), input.description(), input.enabled(), input.isQuiet(), roomId);
    }

    void deleteRoom(long roomId) {
        db.update("DELETE FROM rooms WHERE id=?", roomId);
    }

    // ---------- seats ----------

    long insertSeat(ResourceRequests.Seat input) {
        return db.insert("INSERT INTO seats(room_id,code,has_power,enabled) VALUES(?,?,?,?)",
                input.roomId(), input.code().trim(), input.hasPower(), input.enabled());
    }

    void updateSeat(long seatId, ResourceRequests.Seat input) {
        db.update("UPDATE seats SET code=?,has_power=?,enabled=? WHERE id=?",
                input.code().trim(), input.hasPower(), input.enabled(), seatId);
    }

    void deleteSeat(long seatId) {
        db.update("DELETE FROM seats WHERE id=?", seatId);
    }
}