package vn.edu.phenikaa.alumni.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Seeds synthetic role accounts only in an explicitly enabled local development profile. */
@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "app.demo-accounts", name = "enabled", havingValue = "true")
@Order(1)
public class DemoAccountsBootstrap implements ApplicationRunner {
    private static final String FACULTY_CODE = "CNTT";
    private static final String FACULTY_NAME = "Khoa Công nghệ thông tin";
    private static final List<MajorSeed> MAJORS = List.of(
            new MajorSeed("ICT1", "Công nghệ Thông tin"),
            new MajorSeed("ICT2", "Kỹ thuật Phần mềm"),
            new MajorSeed("ICT3", "Khoa học Máy tính (AI & Data Science)"),
            new MajorSeed("ICT4", "An toàn Thông tin"),
            new MajorSeed("ICT5", "Trí tuệ Nhân tạo"));
    private static final List<CohortSeed> COHORTS = List.of(
            new CohortSeed("K14", 2020, 2024),
            new CohortSeed("K15", 2021, 2025),
            new CohortSeed("K16", 2022, 2026));
    private static final String PENDING_ALUMNI_STUDENT_CODE = "DEMO-ALUMNI-PENDING-001";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final String password;

    public DemoAccountsBootstrap(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                 PasswordPolicy passwordPolicy,
                                 @Value("${app.demo-accounts.password:}") String password) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        passwordPolicy.validate(password);

        long facultyId = ensureFaculty();
        Map<String, Long> majorIds = new LinkedHashMap<>();
        for (MajorSeed major : MAJORS) majorIds.put(major.code(), ensureMajor(facultyId, major));
        Map<String, Long> cohortIds = new LinkedHashMap<>();
        for (CohortSeed cohort : COHORTS) cohortIds.put(cohort.code(), ensureCohort(cohort));
        long majorId = majorIds.get("ICT1");
        long cohortId = cohortIds.get("K16");

        long adminId = ensureAccount("admin.demo@example.com", "Quản trị viên Demo", "ADMIN");
        grantAdminPermissions(adminId);

        long alumniId = ensureAccount("alumni.demo@example.com", "Cựu sinh viên Demo", "ALUMNI");
        ensureAlumniProfile(alumniId, facultyId, majorId, cohortId, adminId);
        ensurePendingAlumniReview(facultyId, majorId, cohortId);

        ensureAccount("student.demo@example.com", "Sinh viên Demo", "STUDENT");

        long employerId = ensureAccount("employer.demo@example.com", "Nhà tuyển dụng Demo", "EMPLOYER");
        ensureEmployerProfile(employerId, adminId);

        long staffId = ensureAccount("staff.demo@example.com", "Cán bộ quản lý Demo", "FACULTY_STAFF");
        ensureStaffScope(staffId, facultyId, adminId);
    }

    private long ensureFaculty() {
        List<FacultyRow> rows = jdbc.query("SELECT id, name, is_active FROM faculties WHERE code = ?",
                (rs, row) -> new FacultyRow(rs.getLong("id"), rs.getString("name"), rs.getBoolean("is_active")),
                FACULTY_CODE);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO faculties (code, name) VALUES (?, ?)", FACULTY_CODE, FACULTY_NAME);
            rows = jdbc.query("SELECT id, name, is_active FROM faculties WHERE code = ?",
                    (rs, row) -> new FacultyRow(rs.getLong("id"), rs.getString("name"), rs.getBoolean("is_active")),
                    FACULTY_CODE);
        }
        FacultyRow faculty = exactlyOne(rows, "khoa demo");
        if (!faculty.active() || !FACULTY_NAME.equals(faculty.name()))
            throw new IllegalStateException("Demo faculty code is already used by a different or inactive catalog entry.");
        return faculty.id();
    }

    private long ensureMajor(long facultyId, MajorSeed seed) {
        List<MajorRow> rows = jdbc.query("SELECT id, faculty_id, name, is_active FROM majors WHERE code = ?",
                (rs, row) -> new MajorRow(rs.getLong("id"), rs.getLong("faculty_id"),
                        rs.getString("name"), rs.getBoolean("is_active")), seed.code());
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO majors (faculty_id, code, name) VALUES (?, ?, ?)",
                    facultyId, seed.code(), seed.name());
            rows = jdbc.query("SELECT id, faculty_id, name, is_active FROM majors WHERE code = ?",
                    (rs, row) -> new MajorRow(rs.getLong("id"), rs.getLong("faculty_id"),
                            rs.getString("name"), rs.getBoolean("is_active")), seed.code());
        }
        MajorRow major = exactlyOne(rows, "ngành demo " + seed.code());
        if (!major.active() || major.facultyId() != facultyId || !seed.name().equals(major.name()))
            throw new IllegalStateException("A demo major code is already used by a different or inactive catalog entry: " + seed.code());
        return major.id();
    }

    private long ensureCohort(CohortSeed seed) {
        List<CohortRow> rows = jdbc.query("SELECT id, admission_year, expected_graduation_year, is_active FROM cohorts WHERE code = ?",
                (rs, row) -> new CohortRow(rs.getLong("id"), rs.getInt("admission_year"),
                        rs.getInt("expected_graduation_year"), rs.getBoolean("is_active")), seed.code());
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO cohorts (code, admission_year, expected_graduation_year) VALUES (?, ?, ?)",
                    seed.code(), seed.admissionYear(), seed.expectedGraduationYear());
            rows = jdbc.query("SELECT id, admission_year, expected_graduation_year, is_active FROM cohorts WHERE code = ?",
                    (rs, row) -> new CohortRow(rs.getLong("id"), rs.getInt("admission_year"),
                            rs.getInt("expected_graduation_year"), rs.getBoolean("is_active")), seed.code());
        }
        CohortRow cohort = exactlyOne(rows, "khóa demo " + seed.code());
        if (!cohort.active() || cohort.admissionYear() != seed.admissionYear()
                || cohort.expectedGraduationYear() != seed.expectedGraduationYear())
            throw new IllegalStateException("A demo cohort code is already used by a different or inactive catalog entry: " + seed.code());
        return cohort.id();
    }

    private long ensureAccount(String email, String fullName, String role) {
        List<AccountRow> rows = jdbc.query("SELECT id, account_status FROM user_accounts WHERE email = ?",
                (rs, row) -> new AccountRow(rs.getLong("id"), rs.getString("account_status")), email);
        if (!rows.isEmpty()) {
            AccountRow existing = exactlyOne(rows, "tài khoản demo");
            if (!"ACTIVE".equals(existing.status()) || !hasRole(existing.id(), role))
                throw new IllegalStateException("A reserved demo email already exists with a different account state or role.");
            jdbc.update("UPDATE user_accounts SET password_hash = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                    passwordEncoder.encode(password), existing.id());
            return existing.id();
        }

        jdbc.update("""
                INSERT INTO user_accounts (email, password_hash, full_name, account_status, email_verified_at)
                VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP(6))
                """, email, passwordEncoder.encode(password), fullName);
        Long accountId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = ?", Long.class, email);
        if (accountId == null) throw new IllegalStateException("Database did not return a demo account id.");
        int assigned = jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT ?, id FROM roles WHERE code = ?
                """, accountId, role);
        if (assigned != 1) throw new IllegalStateException("Required role is missing from the migrated database.");
        return accountId;
    }

    private boolean hasRole(long accountId, String role) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.code = ?
                """, Integer.class, accountId, role);
        return count != null && count == 1;
    }

    private void grantAdminPermissions(long adminId) {
        for (String permission : List.of("EMPLOYER_REVIEW", "JOB_REVIEW", "ALUMNI_IMPORT")) {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_permissions up JOIN permissions p ON p.id = up.permission_id
                    WHERE up.user_id = ? AND p.code = ?
                    """, Integer.class, adminId, permission);
            if (count == null || count == 0) {
                jdbc.update("""
                        INSERT INTO user_permissions (user_id, permission_id)
                        SELECT ?, id FROM permissions WHERE code = ?
                        """, adminId, permission);
            }
        }
    }

    private void ensureAlumniProfile(long accountId, long facultyId, long majorId, long cohortId, long adminId) {
        List<Long> profileIds = jdbc.query("SELECT id FROM alumni_profiles WHERE user_id = ?",
                (rs, row) -> rs.getLong("id"), accountId);
        if (!profileIds.isEmpty()) {
            exactlyOne(profileIds, "linked demo alumni profile");
            return;
        }
        jdbc.update("""
                INSERT INTO alumni_profiles
                    (user_id, student_code, full_name, faculty_id, major_id, cohort_id, graduation_year,
                     verification_status, verified_at, verification_reviewed_by, verification_reviewed_at,
                     directory_visibility)
                VALUES (?, 'DEMO-ALUMNI-001', 'Cựu sinh viên Demo', ?, ?, ?, 2026,
                        'VERIFIED', CURRENT_TIMESTAMP(6), ?, CURRENT_TIMESTAMP(6), 'PRIVATE')
                """, accountId, facultyId, majorId, cohortId, adminId);
    }

    /** Adds a separate synthetic review fixture without changing its decision on later startups. */
    private void ensurePendingAlumniReview(long facultyId, long majorId, long cohortId) {
        List<ReviewFixtureRow> rows = jdbc.query("""
                SELECT faculty_id, major_id, cohort_id
                FROM alumni_profiles
                WHERE student_code = ?
                """, (rs, row) -> new ReviewFixtureRow(
                rs.getLong("faculty_id"), rs.getObject("major_id", Long.class), rs.getLong("cohort_id")),
                PENDING_ALUMNI_STUDENT_CODE);
        if (!rows.isEmpty()) {
            ReviewFixtureRow existing = exactlyOne(rows, "hồ sơ chờ xác minh demo");
            if (existing.facultyId() != facultyId || !Long.valueOf(majorId).equals(existing.majorId())
                    || existing.cohortId() != cohortId)
                throw new IllegalStateException("The reserved demo review profile has an incompatible academic catalog.");
            return;
        }

        jdbc.update("""
                INSERT INTO alumni_profiles
                    (user_id, student_code, full_name, faculty_id, major_id, cohort_id, graduation_year,
                     employment_status, directory_visibility, verification_status)
                VALUES (NULL, ?, 'Hồ sơ chờ xác minh Demo', ?, ?, ?, 2026,
                        'UNKNOWN', 'PRIVATE', 'PENDING')
                """, PENDING_ALUMNI_STUDENT_CODE, facultyId, majorId, cohortId);
    }

    private void ensureEmployerProfile(long accountId, long adminId) {
        List<String> statuses = jdbc.query("SELECT review_status FROM employer_profiles WHERE user_id = ?",
                (rs, row) -> rs.getString(1), accountId);
        if (!statuses.isEmpty()) {
            if (statuses.size() != 1 || !"APPROVED".equals(statuses.get(0)))
                throw new IllegalStateException("The reserved demo employer account has an incompatible profile.");
            return;
        }
        jdbc.update("""
                INSERT INTO employer_profiles
                    (user_id, organization_name, business_registration_number, review_status, reviewed_by, reviewed_at)
                VALUES (?, 'Doanh nghiệp Demo', 'DEMO-BIZ-001', 'APPROVED', ?, CURRENT_TIMESTAMP(6))
                """, accountId, adminId);
    }

    private void ensureStaffScope(long accountId, long facultyId, long adminId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM staff_faculty_scopes WHERE user_id = ? AND faculty_id = ?
                """, Integer.class, accountId, facultyId);
        if (count == null || count == 0) {
            jdbc.update("""
                    INSERT INTO staff_faculty_scopes (user_id, faculty_id, assigned_by)
                    VALUES (?, ?, ?)
                    """, accountId, facultyId, adminId);
        }
    }

    private static <T> T exactlyOne(List<T> rows, String description) {
        if (rows.size() != 1) throw new IllegalStateException("Expected exactly one " + description + ".");
        return rows.get(0);
    }

    private record FacultyRow(long id, String name, boolean active) {}
    private record MajorRow(long id, long facultyId, String name, boolean active) {}
    private record CohortRow(long id, int admissionYear, int expectedGraduationYear, boolean active) {}
    private record AccountRow(long id, String status) {}
    private record ReviewFixtureRow(long facultyId, Long majorId, long cohortId) {}
    private record MajorSeed(String code, String name) {}
    private record CohortSeed(String code, int admissionYear, int expectedGraduationYear) {}
}
