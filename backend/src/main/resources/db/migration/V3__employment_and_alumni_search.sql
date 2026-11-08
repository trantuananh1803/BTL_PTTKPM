ALTER TABLE alumni_profiles
    ADD COLUMN employment_industry VARCHAR(180) NULL AFTER job_title,
    ADD COLUMN employment_updated_at DATETIME(6) NULL AFTER employment_industry,
    ADD INDEX idx_alumni_profiles_employment_status (employment_status, archived_at),
    ADD INDEX idx_alumni_profiles_major_scope (major_id, faculty_id, archived_at),
    ADD INDEX idx_alumni_profiles_graduation_year (graduation_year, archived_at);
