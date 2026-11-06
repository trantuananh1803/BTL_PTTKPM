ALTER TABLE alumni_profiles
    MODIFY user_id BIGINT UNSIGNED NULL,
    ADD COLUMN verification_reviewed_by BIGINT UNSIGNED NULL AFTER verified_at,
    ADD COLUMN verification_reviewed_at DATETIME(6) NULL AFTER verification_reviewed_by,
    ADD COLUMN verification_note VARCHAR(1000) NULL AFTER verification_reviewed_at,
    ADD CONSTRAINT fk_alumni_profiles_verification_reviewer
        FOREIGN KEY (verification_reviewed_by) REFERENCES user_accounts (id) ON DELETE SET NULL;

CREATE TABLE staff_faculty_scopes (
    user_id BIGINT UNSIGNED NOT NULL,
    faculty_id BIGINT UNSIGNED NOT NULL,
    assigned_by BIGINT UNSIGNED NULL,
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, faculty_id),
    CONSTRAINT fk_staff_faculty_scopes_user FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_faculty_scopes_faculty FOREIGN KEY (faculty_id) REFERENCES faculties (id) ON DELETE RESTRICT,
    CONSTRAINT fk_staff_faculty_scopes_assigner FOREIGN KEY (assigned_by) REFERENCES user_accounts (id) ON DELETE SET NULL,
    INDEX idx_staff_faculty_scopes_faculty (faculty_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE employer_profiles (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    organization_name VARCHAR(180) NOT NULL,
    business_registration_number VARCHAR(40) NOT NULL,
    website VARCHAR(255) NULL,
    review_status ENUM('PENDING', 'APPROVED', 'REJECTED', 'NEEDS_CORRECTION') NOT NULL DEFAULT 'PENDING',
    reviewed_by BIGINT UNSIGNED NULL,
    reviewed_at DATETIME(6) NULL,
    review_note VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_employer_profiles_user UNIQUE (user_id),
    CONSTRAINT uq_employer_profiles_registration UNIQUE (business_registration_number),
    CONSTRAINT fk_employer_profiles_user FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_employer_profiles_reviewer FOREIGN KEY (reviewed_by) REFERENCES user_accounts (id) ON DELETE SET NULL,
    INDEX idx_employer_profiles_review_status (review_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE job_postings (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employer_user_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    status ENUM('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'NEEDS_CORRECTION', 'CLOSED') NOT NULL DEFAULT 'DRAFT',
    reviewed_by BIGINT UNSIGNED NULL,
    reviewed_at DATETIME(6) NULL,
    review_note VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_job_postings_employer FOREIGN KEY (employer_user_id) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_postings_reviewer FOREIGN KEY (reviewed_by) REFERENCES user_accounts (id) ON DELETE SET NULL,
    INDEX idx_job_postings_review_status (status, created_at),
    INDEX idx_job_postings_employer (employer_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE alumni_import_batches (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    created_by BIGINT UNSIGNED NOT NULL,
    source_filename VARCHAR(255) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    storage_key CHAR(36) NOT NULL,
    status ENUM('PREVIEW_READY', 'PREVIEW_WITH_ERRORS', 'COMMITTED', 'FAILED', 'EXPIRED') NOT NULL,
    total_rows INT UNSIGNED NOT NULL DEFAULT 0,
    valid_rows INT UNSIGNED NOT NULL DEFAULT 0,
    skipped_rows INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    committed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_alumni_import_batches_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_alumni_import_batches_creator FOREIGN KEY (created_by) REFERENCES user_accounts (id) ON DELETE RESTRICT,
    INDEX idx_alumni_import_batches_creator (created_by, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE alumni_import_row_errors (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    batch_id BIGINT UNSIGNED NOT NULL,
    source_row_number INT UNSIGNED NOT NULL,
    error_code VARCHAR(60) NOT NULL,
    safe_message VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_alumni_import_row_errors_batch FOREIGN KEY (batch_id) REFERENCES alumni_import_batches (id) ON DELETE CASCADE,
    CONSTRAINT uq_alumni_import_row_errors_row_code UNIQUE (batch_id, source_row_number, error_code),
    INDEX idx_alumni_import_row_errors_batch (batch_id, source_row_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
