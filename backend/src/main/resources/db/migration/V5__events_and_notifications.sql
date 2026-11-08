CREATE TABLE events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    description TEXT NULL,
    location VARCHAR(255) NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    registration_opens_at DATETIME(6) NOT NULL,
    registration_closes_at DATETIME(6) NOT NULL,
    capacity INT UNSIGNED NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_events_creator FOREIGN KEY (created_by) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_events_time_order CHECK (
        ends_at > starts_at AND
        registration_opens_at < registration_closes_at AND
        registration_closes_at <= starts_at
    ),
    CONSTRAINT ck_events_capacity CHECK (capacity IS NULL OR capacity > 0),
    INDEX idx_events_starts_at (starts_at, id),
    INDEX idx_events_registration_window (registration_opens_at, registration_closes_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event_staff_assignments (
    event_id BIGINT UNSIGNED NOT NULL,
    staff_account_id BIGINT UNSIGNED NOT NULL,
    assigned_by BIGINT UNSIGNED NOT NULL,
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id, staff_account_id),
    CONSTRAINT fk_event_staff_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_staff_account FOREIGN KEY (staff_account_id) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_staff_assigner FOREIGN KEY (assigned_by) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    INDEX idx_event_staff_account (staff_account_id, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event_registrations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id BIGINT UNSIGNED NOT NULL,
    account_id BIGINT UNSIGNED NOT NULL,
    status ENUM('REGISTERED', 'CANCELLED') NOT NULL DEFAULT 'REGISTERED',
    registered_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    cancelled_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_event_registration_account UNIQUE (event_id, account_id),
    CONSTRAINT fk_event_registrations_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_registrations_account FOREIGN KEY (account_id) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_event_registration_cancelled CHECK (
        (status = 'REGISTERED' AND cancelled_at IS NULL) OR
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
    ),
    INDEX idx_event_registrations_status (event_id, status),
    INDEX idx_event_registrations_account (account_id, registered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_campaigns (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    created_by BIGINT UNSIGNED NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    audience ENUM('ALUMNI', 'STUDENT', 'EVENT_REGISTRANTS') NOT NULL,
    channel ENUM('IN_APP', 'EMAIL') NOT NULL,
    related_event_id BIGINT UNSIGNED NULL,
    status ENUM('SENDING', 'DELIVERED', 'PARTIAL', 'FAILED') NOT NULL,
    recipient_count INT UNSIGNED NOT NULL DEFAULT 0,
    sent_count INT UNSIGNED NOT NULL DEFAULT 0,
    failed_count INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_campaigns_creator FOREIGN KEY (created_by) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_campaigns_event FOREIGN KEY (related_event_id) REFERENCES events (id) ON DELETE SET NULL,
    INDEX idx_notification_campaigns_creator (created_by, created_at),
    INDEX idx_notification_campaigns_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT UNSIGNED NULL,
    recipient_account_id BIGINT UNSIGNED NOT NULL,
    related_event_id BIGINT UNSIGNED NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_notifications_campaign_recipient UNIQUE (campaign_id, recipient_account_id),
    CONSTRAINT fk_notifications_campaign FOREIGN KEY (campaign_id) REFERENCES notification_campaigns (id) ON DELETE SET NULL,
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_account_id) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_notifications_event FOREIGN KEY (related_event_id) REFERENCES events (id) ON DELETE SET NULL,
    INDEX idx_notifications_inbox (recipient_account_id, read_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
