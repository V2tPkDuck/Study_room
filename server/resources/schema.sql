-- 智慧自习室预约与管理系统：课程 Demo 数据库结构（H2）
-- 依据：需求分析报告 v1.2 第 3 章；A 报告 V-01 的“待核查”缓冲。
CREATE TABLE IF NOT EXISTS app_users (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(32) NOT NULL UNIQUE,
 display_name VARCHAR(40) NOT NULL,
 password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(16) NOT NULL DEFAULT 'STUDENT',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 blacklist_until TIMESTAMP NULL,
 created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS rooms (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(60) NOT NULL UNIQUE,
 building VARCHAR(80) NOT NULL,
 floor_no INT NOT NULL,
 open_time VARCHAR(5) NOT NULL,
 close_time VARCHAR(5) NOT NULL,
 description VARCHAR(500) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 is_quiet BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS seats (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 room_id BIGINT NOT NULL,
 code VARCHAR(20) NOT NULL,
 has_power BOOLEAN NOT NULL DEFAULT FALSE,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(room_id,code),
 FOREIGN KEY(room_id) REFERENCES rooms(id)
);

-- 教学模拟参数：报告 6.5 明确这些数值“待确认”，本表只用于课程演示。
CREATE TABLE IF NOT EXISTS reservation_policy (
 id INT PRIMARY KEY,
 max_days INT NOT NULL,
 max_hours INT NOT NULL,
 check_in_before INT NOT NULL,
 grace_minutes INT NOT NULL,
 max_active INT NOT NULL,
 blacklist_threshold INT NOT NULL,
 blacklist_days INT NOT NULL
);

CREATE TABLE IF NOT EXISTS booking_mutex (id INT PRIMARY KEY);

CREATE TABLE IF NOT EXISTS reservations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 seat_id BIGINT NOT NULL,
 start_at TIMESTAMP NOT NULL,
 end_at TIMESTAMP NOT NULL,
 status VARCHAR(20) NOT NULL,
 check_in_at TIMESTAMP NULL,
 check_out_at TIMESTAMP NULL,
 remark VARCHAR(300) NULL,
 created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(user_id) REFERENCES app_users(id),
 FOREIGN KEY(seat_id) REFERENCES seats(id)
);

-- source: AUTO（自动标记后由管理员确认）/ MANUAL（管理员直接登记）
CREATE TABLE IF NOT EXISTS violations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 reservation_id BIGINT NOT NULL UNIQUE,
 source VARCHAR(10) NOT NULL,
 reason VARCHAR(300) NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE,
 revoke_reason VARCHAR(300) NULL,
 created_by VARCHAR(32) NOT NULL,
 created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(user_id) REFERENCES app_users(id),
 FOREIGN KEY(reservation_id) REFERENCES reservations(id)
);

-- 黑名单记录：满足报告 3.2 的 BlacklistRecord（原因、起止、解除记录）。
CREATE TABLE IF NOT EXISTS blacklist_records (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 reason VARCHAR(300) NOT NULL,
 start_at TIMESTAMP NOT NULL,
 end_at TIMESTAMP NOT NULL,
 created_by VARCHAR(32) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 released_at TIMESTAMP NULL,
 released_by VARCHAR(32) NULL,
 release_reason VARCHAR(300) NULL,
 FOREIGN KEY(user_id) REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS complaints (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 reservation_id BIGINT NULL,
 topic VARCHAR(80) NOT NULL,
 content VARCHAR(2000) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 reply VARCHAR(2000) NOT NULL DEFAULT '',
 replied_by BIGINT NULL,
 created_at TIMESTAMP NOT NULL,
 updated_at TIMESTAMP NOT NULL,
 FOREIGN KEY(user_id) REFERENCES app_users(id),
 FOREIGN KEY(reservation_id) REFERENCES reservations(id),
 FOREIGN KEY(replied_by) REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS reviews (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 reservation_id BIGINT NOT NULL UNIQUE,
 rating INT NOT NULL,
 content VARCHAR(1000) NOT NULL,
 visible BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP NOT NULL,
 FOREIGN KEY(user_id) REFERENCES app_users(id),
 FOREIGN KEY(reservation_id) REFERENCES reservations(id)
);

CREATE TABLE IF NOT EXISTS audit_logs (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 actor VARCHAR(32) NOT NULL,
 action VARCHAR(50) NOT NULL,
 target_type VARCHAR(30) NOT NULL,
 target_id BIGINT NULL,
 detail VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP NOT NULL
);
