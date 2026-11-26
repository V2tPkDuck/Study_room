package studyroom.resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studyroom.platform.IdentityService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;
import static studyroom.platform.Store.id;

/**
 * FR-13: seat maintenance, guarded by active reservations and room membership.
 */
@Service
public class SeatAdminService {
    private final Store db;
    private final IdentityService identity;
    private final ResourceRepository rooms;

    public SeatAdminService(Store db, IdentityService identity, ResourceRepository rooms) {
        this.db = db;
        this.identity = identity;
        this.rooms = rooms;
    }

    @Transactional
    public long saveSeat(Long seatId, ResourceRequests.Seat input) {
        db.lock();
        var admin = identity.admin();
        rooms.requireRoom(input.roomId());

        if (seatId == null) {
            seatId = rooms.insertSeat(input);
        } else {
            var old = rooms.requireSeat(seatId);
            require(id(old, "room_id") == input.roomId(), 400, "已有座位不能转移到其他自习室");
            require(rooms.countActiveReservationsOfSeat(seatId) == 0,
                    409, "座位仍有有效预约，请先处理预约");
            rooms.updateSeat(seatId, input);
        }
        identity.audit((String) admin.get("username"), "SAVE_SEAT", "SEAT", seatId, input.code());
        return seatId;
    }

    @Transactional
    public void delete(long seatId) {
        db.lock();
        var admin = identity.admin();
        rooms.requireSeat(seatId);
        require(rooms.countReservationsOfSeat(seatId) == 0,
                409, "座位已有预约历史，请使用停用功能");
        rooms.deleteSeat(seatId);
        identity.audit((String) admin.get("username"), "DELETE_RESOURCE", "SEAT", seatId,
                "删除无历史记录的资源");
    }
}