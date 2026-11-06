CREATE TABLE roles (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_roles_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE permissions (
    id SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(60) NOT NULL,
    description VARCHAR(180) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_permissions_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_accounts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NULL,
    full_name VARCHAR(180) NOT NULL,
    account_status ENUM('PENDING_EMAIL', 'ACTIVE', 'LOCKED', 'DISABLED') NOT NULL DEFAULT 'PENDING_EMAIL',
    email_verified_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_user_accounts_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    user_id BIGINT UNSIGNED NOT NULL,
    role_id SMALLINT UNSIGNED NOT NULL,
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT,
    INDEX idx_user_roles_role (role_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_permissions (
    user_id BIGINT UNSIGNED NOT NULL,
    permission_id SMALLINT UNSIGNED NOT NULL,
    granted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, permission_id),
    CONSTRAINT fk_user_permissions_user FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE faculties (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(180) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_faculties_code UNIQUE (code),
    INDEX idx_faculties_active_name (is_active, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE majors (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    faculty_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(180) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_majors_code UNIQUE (code),
    CONSTRAINT uq_majors_id_faculty UNIQUE (id, faculty_id),
    CONSTRAINT fk_majors_faculty FOREIGN KEY (faculty_id) REFERENCES faculties (id) ON DELETE RESTRICT,
    INDEX idx_majors_faculty_active (faculty_id, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cohorts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL,
    admission_year SMALLINT UNSIGNED NOT NULL,
    expected_graduation_year SMALLINT UNSIGNED NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_cohorts_code UNIQUE (code),
    CONSTRAINT ck_cohorts_years CHECK (expected_graduation_year >= admission_year),
    INDEX idx_cohorts_active_years (is_active, admission_year, expected_graduation_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE alumni_profiles (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    student_code VARCHAR(32) NOT NULL,
    full_name VARCHAR(180) NOT NULL,
    faculty_id BIGINT UNSIGNED NOT NULL,
    major_id BIGINT UNSIGNED NULL,
    cohort_id BIGINT UNSIGNED NOT NULL,
    graduation_year SMALLINT UNSIGNED NOT NULL,
    personal_email VARCHAR(254) NULL,
    phone VARCHAR(32) NULL,
    employment_status ENUM('UNKNOWN', 'EMPLOYED', 'SEEKING', 'STUDYING', 'OTHER') NOT NULL DEFAULT 'UNKNOWN',
    employer_name VARCHAR(180) NULL,
    job_title VARCHAR(180) NULL,
    directory_visibility ENUM('PRIVATE', 'VERIFIED_USERS') NOT NULL DEFAULT 'PRIVATE',
    verification_status ENUM('PENDING', 'VERIFIED', 'NEEDS_CORRECTION') NOT NULL DEFAULT 'PENDING',
    verified_at DATETIME(6) NULL,
    archived_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_alumni_profiles_user UNIQUE (user_id),
    CONSTRAINT uq_alumni_profiles_student_code UNIQUE (student_code),
    CONSTRAINT fk_alumni_profiles_user FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alumni_profiles_faculty FOREIGN KEY (faculty_id) REFERENCES faculties (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alumni_profiles_major_faculty FOREIGN KEY (major_id, faculty_id) REFERENCES majors (id, faculty_id) ON DELETE RESTRICT,
    CONSTRAINT fk_alumni_profiles_cohort FOREIGN KEY (cohort_id) REFERENCES cohorts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_alumni_profiles_graduation_year CHECK (graduation_year BETWEEN 1900 AND 2200),
    INDEX idx_alumni_profiles_faculty_status (faculty_id, verification_status, archived_at),
    INDEX idx_alumni_profiles_cohort (cohort_id),
    INDEX idx_alumni_profiles_name (full_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE profile_change_requests (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    alumni_profile_id BIGINT UNSIGNED NOT NULL,
    submitted_by BIGINT UNSIGNED NOT NULL,
    proposed_values JSON NOT NULL,
    status ENUM('PENDING', 'APPROVED', 'REJECTED') NOT NULL DEFAULT 'PENDING',
    reviewed_by BIGINT UNSIGNED NULL,
    review_note VARCHAR(1000) NULL,
    submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reviewed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_profile_change_requests_profile FOREIGN KEY (alumni_profile_id) REFERENCES alumni_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_profile_change_requests_submitter FOREIGN KEY (submitted_by) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_profile_change_requests_reviewer FOREIGN KEY (reviewed_by) REFERENCES user_accounts (id) ON DELETE SET NULL,
    CONSTRAINT ck_profile_change_requests_review_time CHECK ((status = 'PENDING' AND reviewed_at IS NULL) OR (status <> 'PENDING' AND reviewed_at IS NOT NULL)),
    INDEX idx_profile_change_requests_profile_status (alumni_profile_id, status, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE otp_challenges (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id BIGINT UNSIGNED NULL,
    recipient_hash CHAR(64) NOT NULL,
    requester_ip_hash CHAR(64) NOT NULL,
    purpose ENUM('REGISTER', 'PASSWORD_RECOVERY') NOT NULL,
    code_hash VARCHAR(100) NULL,
    delivery_status ENUM('PENDING', 'SENT', 'FAILED', 'RATE_LIMITED', 'NOT_SENT') NOT NULL,
    attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    invalidated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_otp_challenges_account FOREIGN KEY (account_id) REFERENCES user_accounts (id) ON DELETE SET NULL,
    CONSTRAINT ck_otp_challenges_attempts CHECK (attempts <= 5),
    INDEX idx_otp_challenges_recipient_purpose_created (recipient_hash, purpose, created_at),
    INDEX idx_otp_challenges_ip_created (requester_ip_hash, created_at),
    INDEX idx_otp_challenges_active (recipient_hash, purpose, consumed_at, invalidated_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE private_files (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_account_id BIGINT UNSIGNED NOT NULL,
    purpose ENUM('AVATAR', 'CV') NOT NULL,
    storage_key VARCHAR(120) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_private_files_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_private_files_owner FOREIGN KEY (owner_account_id) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    INDEX idx_private_files_owner_purpose (owner_account_id, purpose)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_account_id BIGINT UNSIGNED NULL,
    action_code VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(100) NULL,
    changed_fields JSON NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_account_id) REFERENCES user_accounts (id) ON DELETE SET NULL,
    INDEX idx_audit_logs_actor_time (actor_account_id, occurred_at),
    INDEX idx_audit_logs_entity_time (entity_type, entity_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO roles (code, name) VALUES
    ('ALUMNI', 'Cựu sinh viên'),
    ('STUDENT', 'Sinh viên'),
    ('EMPLOYER', 'Nhà tuyển dụng'),
    ('FACULTY_STAFF', 'Cán bộ quản lý'),
    ('ADMIN', 'Quản trị viên');

INSERT INTO permissions (code, description) VALUES
    ('EMPLOYER_REVIEW', 'Duyệt hồ sơ doanh nghiệp'),
    ('JOB_REVIEW', 'Duyệt tin tuyển dụng'),
    ('ALUMNI_IMPORT', 'Nhập hồ sơ cựu sinh viên');
