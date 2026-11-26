package studyroom.resource;

import java.time.LocalTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import studyroom.platform.IdentityService;
import studyroom.platform.Store;
import static studyroom.platform.BusinessException.require;

/** FR-12: room maintenance, guarded by active reservations. */
@Service
public class RoomService {
    private final Store db;
    private final IdentityService identity;
    private final ResourceRepository rooms;

    public RoomService(Store db, IdentityService identity, ResourceRepository rooms) {
        this.db = db;
        this.identity = identity;
        this.rooms = rooms;
    }

    @Transactional
    public long saveRoom(Long roomId, ResourceRequests.Room input) {
        db.lock();
        var admin = identity.admin();
        require(input.openTime() != null && input.closeTime() != null, 400, "开放时间不能为空");
        require(LocalTime.parse(input.openTime()).isBefore(LocalTime.parse(input.closeTime())),
                400, "开放时间需早于关闭时间，不支持跨夜开放");

        if (roomId == null) {
            roomId = rooms.insertRoom(input);
        } else {
            rooms.requireRoom(roomId);
            require(rooms.countActiveReservationsOfRoom(roomId) == 0,
                    409, "该自习室仍有有效预约，请先处理预约再修改");
            rooms.updateRoom(roomId, input);
        }
        identity.audit((String) admin.get("username"), "SAVE_ROOM", "ROOM", roomId, input.name());
        return roomId;
    }

    @Transactional
    public void delete(long roomId) {
        db.lock();
        var admin = identity.admin();
        rooms.requireRoom(roomId);
        require(rooms.countSeats(roomId) == 0,
                409, "请先删除该自习室的座位；有历史记录的资源请停用");
        rooms.deleteRoom(roomId);
        identity.audit((String) admin.get("username"), "DELETE_RESOURCE", "ROOM", roomId,
                "删除无历史记录的资源");
    }
}