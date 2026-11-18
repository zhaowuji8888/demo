CREATE TABLE rooms (
    id INT PRIMARY KEY,
    label VARCHAR(40) NOT NULL,
    capacity INT NOT NULL,
    enabled INT NOT NULL DEFAULT 1,
    equipment VARCHAR(120) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    deleted INT NOT NULL DEFAULT 0,
    location VARCHAR(160) NOT NULL DEFAULT '',
    description VARCHAR(500) NOT NULL DEFAULT '',
    image_url VARCHAR(500) NOT NULL DEFAULT '',
    approval INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_ids (
    id INT AUTO_INCREMENT PRIMARY KEY
) ENGINE=InnoDB AUTO_INCREMENT=1000;

CREATE TABLE accounts (
    username VARCHAR(40) PRIMARY KEY,
    salt VARCHAR(64) NOT NULL,
    verifier VARCHAR(128) NOT NULL,
    is_admin INT NOT NULL,
    strikes INT NOT NULL DEFAULT 0,
    suspended_until BIGINT NOT NULL DEFAULT 0,
    email VARCHAR(254)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bookings (
    id VARCHAR(36) PRIMARY KEY,
    room INT NOT NULL,
    booked_day DATE NOT NULL,
    first_min INT NOT NULL,
    last_min INT NOT NULL,
    holder VARCHAR(40) NOT NULL,
    owner_name VARCHAR(40) NOT NULL,
    needs VARCHAR(120) NOT NULL DEFAULT '',
    attendees INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    cancelled_at TIMESTAMP NULL,
    cancelled_by VARCHAR(40),
    series_id VARCHAR(36),
    checked_in_at TIMESTAMP NULL,
    reminded INT NOT NULL DEFAULT 0,
    checkin_required SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT booking_room_fk FOREIGN KEY (room) REFERENCES rooms(id),
    CONSTRAINT booking_account_fk FOREIGN KEY (owner_name) REFERENCES accounts(username),
    INDEX agenda (booked_day, room, first_min),
    INDEX bookings_owner_day (owner_name, booked_day),
    INDEX booking_status (status, booked_day),
    INDEX booking_series (series_id, booked_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE slots (
    room INT NOT NULL,
    booked_day DATE NOT NULL,
    minute_of_day INT NOT NULL,
    booking VARCHAR(36) NOT NULL,
    PRIMARY KEY (room, booked_day, minute_of_day),
    CONSTRAINT slot_room_fk FOREIGN KEY (room) REFERENCES rooms(id),
    CONSTRAINT slot_booking_fk FOREIGN KEY (booking) REFERENCES bookings(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE maintenance (
    id VARCHAR(36) PRIMARY KEY,
    room INT NOT NULL,
    booked_day DATE NOT NULL,
    first_min INT NOT NULL,
    last_min INT NOT NULL,
    reason VARCHAR(200) NOT NULL,
    CONSTRAINT maintenance_room_fk FOREIGN KEY (room) REFERENCES rooms(id),
    INDEX maintenance_day (room, booked_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_hours (
    room INT NOT NULL,
    weekday INT NOT NULL,
    opens INT NOT NULL,
    closes INT NOT NULL,
    PRIMARY KEY (room, weekday),
    CONSTRAINT hours_room_fk FOREIGN KEY (room) REFERENCES rooms(id)
) ENGINE=InnoDB;

CREATE TABLE holidays (
    booked_day DATE PRIMARY KEY
) ENGINE=InnoDB;

CREATE TABLE day_override (
    room INT NOT NULL,
    booked_day DATE NOT NULL,
    opens INT NOT NULL,
    closes INT NOT NULL,
    PRIMARY KEY (room, booked_day),
    CONSTRAINT override_room_fk FOREIGN KEY (room) REFERENCES rooms(id)
) ENGINE=InnoDB;

CREATE TABLE admin_log (
    seq BIGINT AUTO_INCREMENT PRIMARY KEY,
    at_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor VARCHAR(40),
    operation VARCHAR(32),
    target VARCHAR(40),
    reason VARCHAR(200),
    INDEX audit_time (at_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE login_guard (
    username VARCHAR(40) PRIMARY KEY,
    failures INT NOT NULL,
    locked_until BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notices (
    id VARCHAR(36) PRIMARY KEY,
    recipient VARCHAR(40) NOT NULL,
    booking VARCHAR(36) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    body VARCHAR(400) NOT NULL,
    state VARCHAR(12) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX notices_recipient_created (recipient, created),
    INDEX notices_state_created (state, created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO rooms (id, label, capacity, equipment) VALUES
    (1, 'Cedar', 4, 'whiteboard'),
    (2, 'Maple', 8, 'projector,whiteboard'),
    (3, 'Oak', 12, 'camera,projector,whiteboard');
