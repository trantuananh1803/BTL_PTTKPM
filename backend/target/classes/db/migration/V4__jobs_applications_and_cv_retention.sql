ALTER TABLE private_files
    ADD CONSTRAINT uq_private_files_id_owner UNIQUE (id, owner_account_id);

ALTER TABLE job_postings
    ADD COLUMN department VARCHAR(180) NULL AFTER title,
    ADD COLUMN location VARCHAR(180) NULL AFTER department,
    ADD COLUMN employment_type ENUM('FULL_TIME', 'PART_TIME', 'INTERNSHIP') NULL AFTER location,
    ADD COLUMN requirements TEXT NULL AFTER description,
    ADD COLUMN application_deadline DATE NULL AFTER requirements,
    ADD COLUMN closed_at DATETIME(6) NULL AFTER reviewed_at,
    ADD INDEX idx_job_postings_public_search (status, application_deadline, employment_type),
    ADD INDEX idx_job_postings_location (location);

CREATE TABLE job_applications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_posting_id BIGINT UNSIGNED NOT NULL,
    applicant_account_id BIGINT UNSIGNED NOT NULL,
    cv_file_id BIGINT UNSIGNED NULL,
    status ENUM('SUBMITTED') NOT NULL DEFAULT 'SUBMITTED',
    submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_job_applications_job_applicant UNIQUE (job_posting_id, applicant_account_id),
    CONSTRAINT uq_job_applications_cv_file UNIQUE (cv_file_id),
    CONSTRAINT fk_job_applications_job FOREIGN KEY (job_posting_id)
        REFERENCES job_postings (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_applications_applicant FOREIGN KEY (applicant_account_id)
        REFERENCES user_accounts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_applications_cv_owner FOREIGN KEY (cv_file_id, applicant_account_id)
        REFERENCES private_files (id, owner_account_id) ON DELETE RESTRICT,
    INDEX idx_job_applications_applicant_time (applicant_account_id, submitted_at),
    INDEX idx_job_applications_job_time (job_posting_id, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
