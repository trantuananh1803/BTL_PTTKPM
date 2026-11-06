package vn.edu.phenikaa.alumni.profile;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.phenikaa.alumni.api.AlumniManagementDtos;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AlumniManagementService {
    private static final Set<String> EMPLOYMENT_STATUSES = Set.of("UNKNOWN", "EMPLOYED", "SEEKING", "STUDYING", "OTHER");
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "VERIFIED_USERS");
    private static final Set<String> PATCH_FIELDS = Set.of("personalEmail", "phone", "employmentStatus",
            "employerName", "jobTitle", "industry", "directoryVisibility");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AlumniManagementService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public AlumniManagementDtos.CatalogOptions catalogOptions(AccountPrincipal principal) {
        List<AlumniManagementDtos.CatalogOption> faculties;
        List<AlumniManagementDtos.CatalogOption> majors;
        if (isAdmin(principal)) {
            faculties = jdbc.query("SELECT id, code, name FROM faculties WHERE is_active = TRUE ORDER BY name",
                    (rs, row) -> new AlumniManagementDtos.CatalogOption(rs.getLong("id"), rs.getString("code"), rs.getString("name"), null));
            majors = jdbc.query("SELECT id, code, name, faculty_id FROM majors WHERE is_active = TRUE ORDER BY name",
                    (rs, row) -> new AlumniManagementDtos.CatalogOption(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getLong("faculty_id")));
        } else {
            faculties = jdbc.query("""
                    SELECT f.id, f.code, f.name FROM faculties f
                    JOIN staff_faculty_scopes s ON s.faculty_id = f.id
                    WHERE s.user_id = ? AND f.is_active = TRUE ORDER BY f.name
                    """, (rs, row) -> new AlumniManagementDtos.CatalogOption(rs.getLong("id"), rs.getString("code"), rs.getString("name"), null), principal.id());
            majors = jdbc.query("""
                    SELECT m.id, m.code, m.name, m.faculty_id FROM majors m
                    JOIN staff_faculty_scopes s ON s.faculty_id = m.faculty_id
                    WHERE s.user_id = ? AND m.is_active = TRUE ORDER BY m.name
                    """, (rs, row) -> new AlumniManagementDtos.CatalogOption(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getLong("faculty_id")), principal.id());
        }
        List<AlumniManagementDtos.CohortOption> cohorts = jdbc.query("""
                SELECT id, code, admission_year, expected_graduation_year FROM cohorts
                WHERE is_active = TRUE ORDER BY admission_year DESC
                """, (rs, row) -> new AlumniManagementDtos.CohortOption(rs.getLong("id"), rs.getString("code"),
                rs.getInt("admission_year"), rs.getInt("expected_graduation_year")));
        return new AlumniManagementDtos.CatalogOptions(faculties, majors, cohorts);
    }

    public AlumniManagementDtos.ProfilePage list(AccountPrincipal principal, String q, Long facultyId,
                                                   Long majorId, Long cohortId, Integer graduationYear,
                                                   String employmentStatus, boolean includeArchived,
                                                   int page, int size) {
        validatePage(page, size);
        validateOptionalId(facultyId, "facultyId");
        validateOptionalId(majorId, "majorId");
        validateOptionalId(cohortId, "cohortId");
        if (graduationYear != null && (graduationYear < 1900 || graduationYear > 2200))
            throw invalid("graduationYear", "Năm tốt nghiệp phải nằm trong 1900–2200.");
        if (q != null && q.trim().length() > 180) throw invalid("q", "Từ khóa tối đa 180 ký tự.");
        if (!isAdmin(principal) && facultyId != null) requireFacultyScope(principal.id(), facultyId);
        if (majorId != null) {
            Long majorFaculty = jdbc.query("SELECT faculty_id FROM majors WHERE id = ?", rs -> rs.next() ? rs.getLong(1) : null, majorId);
            if (majorFaculty == null) throw invalid("majorId", "Ngành không tồn tại.");
            if (facultyId != null && !facultyId.equals(majorFaculty)) throw invalid("majorId", "Ngành không thuộc khoa đã chọn.");
            if (!isAdmin(principal)) requireFacultyScope(principal.id(), majorFaculty);
        }
        String status = employmentStatus == null || employmentStatus.isBlank() ? null : normalizeStatus(employmentStatus);

        StringBuilder from = new StringBuilder(" FROM alumni_profiles p JOIN faculties f ON f.id = p.faculty_id LEFT JOIN majors m ON m.id = p.major_id JOIN cohorts c ON c.id = p.cohort_id WHERE 1=1 ");
        List<Object> parameters = new ArrayList<>();
        if (!includeArchived) from.append(" AND p.archived_at IS NULL ");
        if (!isAdmin(principal)) {
            from.append(" AND EXISTS (SELECT 1 FROM staff_faculty_scopes s WHERE s.user_id = ? AND s.faculty_id = p.faculty_id) ");
            parameters.add(principal.id());
        }
        if (q != null && !q.isBlank()) {
            from.append(" AND (p.full_name LIKE ? OR p.student_code LIKE ?) ");
            String query = "%" + q.trim() + "%";
            parameters.add(query);
            parameters.add(query);
        }
        addFilter(from, parameters, "p.faculty_id", facultyId);
        addFilter(from, parameters, "p.major_id", majorId);
        addFilter(from, parameters, "p.cohort_id", cohortId);
        addFilter(from, parameters, "p.graduation_year", graduationYear);
        if (status != null) {
            from.append(" AND p.employment_status = ? ");
            parameters.add(status);
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, parameters.toArray());
        List<Object> rowParameters = new ArrayList<>(parameters);
        rowParameters.add(size);
        rowParameters.add((long) page * size);
        List<AlumniManagementDtos.ProfileSummary> items = jdbc.query("""
                SELECT p.id, p.student_code, p.full_name, p.faculty_id, f.name AS faculty_name,
                       p.major_id, m.name AS major_name, p.cohort_id, c.code AS cohort_code,
                       p.graduation_year, p.employment_status, p.verification_status, p.archived_at
                """ + from + " ORDER BY p.full_name, p.id LIMIT ? OFFSET ?",
                (rs, row) -> summary(rs), rowParameters.toArray());
        return new AlumniManagementDtos.ProfilePage(items, page, size, total == null ? 0 : total);
    }

    public AlumniManagementDtos.ProfileDetail get(long profileId, AccountPrincipal principal) {
        List<AlumniManagementDtos.ProfileDetail> rows = jdbc.query("""
                SELECT p.id, p.student_code, p.full_name, p.faculty_id, f.name AS faculty_name,
                       p.major_id, m.name AS major_name, p.cohort_id, c.code AS cohort_code,
                       p.graduation_year, p.personal_email, p.phone, p.employment_status,
                       p.employer_name, p.job_title, p.employment_industry, p.directory_visibility,
                       p.verification_status, p.archived_at, p.updated_at
                FROM alumni_profiles p
                JOIN faculties f ON f.id = p.faculty_id
                LEFT JOIN majors m ON m.id = p.major_id
                JOIN cohorts c ON c.id = p.cohort_id
                WHERE p.id = ? AND (? = TRUE OR EXISTS (
                    SELECT 1 FROM staff_faculty_scopes s WHERE s.user_id = ? AND s.faculty_id = p.faculty_id
                ))
                """, (rs, row) -> detail(rs), profileId, isAdmin(principal), principal.id());
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    @Transactional
    public AlumniManagementDtos.ProfileDetail create(AlumniManagementDtos.CreateRequest request, AccountPrincipal principal) {
        requireFacultyScopeIfStaff(principal, request.facultyId());
        validateReferences(request.facultyId(), request.majorId(), request.cohortId(), request.graduationYear());
        String studentCode = required(request.studentCode(), "studentCode", 32);
        String fullName = required(request.fullName(), "fullName", 180);
        String personalEmail = email(request.personalEmail());
        String phone = optional(request.phone(), "phone", 32);
        String status = request.employmentStatus() == null ? "UNKNOWN" : normalizeStatus(request.employmentStatus());
        String visibility = request.directoryVisibility() == null ? "PRIVATE" : normalizeVisibility(request.directoryVisibility());
        String employer = optional(request.employerName(), "employerName", 180);
        String jobTitle = optional(request.jobTitle(), "jobTitle", 180);
        String industry = optional(request.industry(), "industry", 180);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO alumni_profiles
                          (user_id, student_code, full_name, faculty_id, major_id, cohort_id, graduation_year,
                           personal_email, phone, employment_status, employer_name, job_title, employment_industry,
                           directory_visibility, verification_status)
                        VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, studentCode);
                statement.setString(2, fullName);
                statement.setLong(3, request.facultyId());
                if (request.majorId() == null) statement.setNull(4, java.sql.Types.BIGINT); else statement.setLong(4, request.majorId());
                statement.setLong(5, request.cohortId());
                statement.setInt(6, request.graduationYear());
                statement.setString(7, personalEmail);
                statement.setString(8, phone);
                statement.setString(9, status);
                statement.setString(10, employer);
                statement.setString(11, jobTitle);
                statement.setString(12, industry);
                statement.setString(13, visibility);
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "ALUMNI_PROFILE_CONFLICT", "Mã sinh viên đã tồn tại.");
        }
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return an alumni profile id");
        long profileId = key.longValue();
        audit(principal.id(), "ALUMNI_PROFILE_CREATED", profileId,
                List.of("studentCode", "fullName", "facultyId", "majorId", "cohortId", "graduationYear"));
        return get(profileId, principal);
    }

    @Transactional
    public AlumniManagementDtos.ProfileDetail update(long profileId, Map<String, Object> request, AccountPrincipal principal) {
        AlumniManagementDtos.ProfileDetail before = get(profileId, principal);
        if (before.archivedAt() != null) throw new ApiException(HttpStatus.CONFLICT, "ALUMNI_PROFILE_ARCHIVED", "Hồ sơ đã lưu trữ, không thể cập nhật.");
        if (request == null || !PATCH_FIELDS.containsAll(request.keySet()))
            throw invalid("profile", "Chỉ được cập nhật email, điện thoại, việc làm và quyền hiển thị; định danh/học vụ phải qua xác minh.");
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (request.containsKey("personalEmail")) normalized.put("personal_email", email(objectString(request.get("personalEmail"), "personalEmail", 254)));
        if (request.containsKey("phone")) normalized.put("phone", optional(objectString(request.get("phone"), "phone", 32), "phone", 32));
        if (request.containsKey("employmentStatus")) normalized.put("employment_status", normalizeStatus(objectString(request.get("employmentStatus"), "employmentStatus", 20)));
        if (request.containsKey("employerName")) normalized.put("employer_name", optional(objectString(request.get("employerName"), "employerName", 180), "employerName", 180));
        if (request.containsKey("jobTitle")) normalized.put("job_title", optional(objectString(request.get("jobTitle"), "jobTitle", 180), "jobTitle", 180));
        if (request.containsKey("industry")) normalized.put("employment_industry", optional(objectString(request.get("industry"), "industry", 180), "industry", 180));
        if (request.containsKey("directoryVisibility")) normalized.put("directory_visibility", normalizeVisibility(objectString(request.get("directoryVisibility"), "directoryVisibility", 40)));

        List<String> changed = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        for (Map.Entry<String, Object> entry : normalized.entrySet()) {
            String apiField = switch (entry.getKey()) {
                case "personal_email" -> "personalEmail";
                case "employment_status" -> "employmentStatus";
                case "employer_name" -> "employerName";
                case "job_title" -> "jobTitle";
                case "employment_industry" -> "industry";
                case "directory_visibility" -> "directoryVisibility";
                default -> entry.getKey();
            };
            if (!java.util.Objects.equals(currentValue(before, apiField), entry.getValue())) {
                assignments.add(entry.getKey() + " = ?");
                parameters.add(entry.getValue());
                changed.add(apiField);
            }
        }
        if (!changed.isEmpty()) {
            assignments.add("updated_at = CURRENT_TIMESTAMP(6)");
            if (changed.contains("employmentStatus") || changed.contains("employerName") || changed.contains("jobTitle") || changed.contains("industry"))
                assignments.add("employment_updated_at = CURRENT_TIMESTAMP(6)");
            parameters.add(profileId);
            jdbc.update("UPDATE alumni_profiles SET " + String.join(", ", assignments) + " WHERE id = ? AND archived_at IS NULL", parameters.toArray());
            audit(principal.id(), "ALUMNI_PROFILE_UPDATED", profileId, changed);
        }
        return get(profileId, principal);
    }

    @Transactional
    public AlumniManagementDtos.ProfileDetail archive(long profileId, AccountPrincipal principal) {
        AlumniManagementDtos.ProfileDetail current = get(profileId, principal);
        if (current.archivedAt() == null) {
            jdbc.update("UPDATE alumni_profiles SET archived_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND archived_at IS NULL", profileId);
            audit(principal.id(), "ALUMNI_PROFILE_ARCHIVED", profileId, List.of("archivedAt"));
        }
        return get(profileId, principal);
    }

    @Transactional
    public AlumniManagementDtos.ProfileDetail restore(long profileId, AccountPrincipal principal) {
        AlumniManagementDtos.ProfileDetail current = get(profileId, principal);
        if (current.archivedAt() != null) {
            jdbc.update("UPDATE alumni_profiles SET archived_at = NULL, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND archived_at IS NOT NULL", profileId);
            audit(principal.id(), "ALUMNI_PROFILE_RESTORED", profileId, List.of("archivedAt"));
        }
        return get(profileId, principal);
    }

    private void validateReferences(long facultyId, Long majorId, long cohortId, int graduationYear) {
        if (graduationYear < 1900 || graduationYear > 2200) throw invalid("graduationYear", "Năm tốt nghiệp phải nằm trong 1900–2200.");
        if (count("SELECT COUNT(*) FROM faculties WHERE id = ? AND is_active = TRUE", facultyId) == 0)
            throw invalid("facultyId", "Khoa không tồn tại hoặc đã ngừng hoạt động.");
        if (count("SELECT COUNT(*) FROM cohorts WHERE id = ? AND is_active = TRUE", cohortId) == 0)
            throw invalid("cohortId", "Khóa không tồn tại hoặc đã ngừng hoạt động.");
        if (majorId != null && count("SELECT COUNT(*) FROM majors WHERE id = ? AND faculty_id = ? AND is_active = TRUE", majorId, facultyId) == 0)
            throw invalid("majorId", "Ngành không hoạt động hoặc không thuộc khoa đã chọn.");
    }

    private void requireFacultyScopeIfStaff(AccountPrincipal principal, long facultyId) {
        if (!isAdmin(principal)) requireFacultyScope(principal.id(), facultyId);
    }

    private void requireFacultyScope(long accountId, long facultyId) {
        if (count("SELECT COUNT(*) FROM staff_faculty_scopes WHERE user_id = ? AND faculty_id = ?", accountId, facultyId) == 0)
            throw new ApiException(HttpStatus.FORBIDDEN, "FACULTY_SCOPE_DENIED", "Khoa nằm ngoài phạm vi được phân công.");
    }

    private void audit(long actorId, String action, long profileId, List<String> fields) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields)
                VALUES (?, ?, 'ALUMNI_PROFILE', ?, CAST(? AS JSON))
                """, actorId, action, Long.toString(profileId), json(fields));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not serialize profile data", exception); }
    }

    private static AlumniManagementDtos.ProfileSummary summary(ResultSet rs) throws SQLException {
        return new AlumniManagementDtos.ProfileSummary(rs.getLong("id"), rs.getString("student_code"), rs.getString("full_name"),
                rs.getLong("faculty_id"), rs.getString("faculty_name"), nullableLong(rs.getObject("major_id")),
                rs.getString("major_name"), rs.getLong("cohort_id"), rs.getString("cohort_code"),
                nullableInt(rs.getObject("graduation_year")), rs.getString("employment_status"),
                rs.getString("verification_status"), instant(rs.getTimestamp("archived_at")));
    }

    private static AlumniManagementDtos.ProfileDetail detail(ResultSet rs) throws SQLException {
        return new AlumniManagementDtos.ProfileDetail(rs.getLong("id"), rs.getString("student_code"), rs.getString("full_name"),
                rs.getLong("faculty_id"), rs.getString("faculty_name"), nullableLong(rs.getObject("major_id")), rs.getString("major_name"),
                rs.getLong("cohort_id"), rs.getString("cohort_code"), nullableInt(rs.getObject("graduation_year")),
                rs.getString("personal_email"), rs.getString("phone"), rs.getString("employment_status"),
                rs.getString("employer_name"), rs.getString("job_title"), rs.getString("employment_industry"),
                rs.getString("directory_visibility"), rs.getString("verification_status"),
                instant(rs.getTimestamp("archived_at")), instant(rs.getTimestamp("updated_at")));
    }

    private static Long nullableLong(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private static Integer nullableInt(Object value) { return value instanceof Number n ? n.intValue() : null; }
    private static java.time.Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static void addFilter(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value != null) { sql.append(" AND ").append(column).append(" = ? "); args.add(value); }
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private static Object currentValue(AlumniManagementDtos.ProfileDetail profile, String field) {
        return switch (field) {
            case "personalEmail" -> profile.personalEmail();
            case "phone" -> profile.phone();
            case "employmentStatus" -> profile.employmentStatus();
            case "employerName" -> profile.employerName();
            case "jobTitle" -> profile.jobTitle();
            case "industry" -> profile.industry();
            case "directoryVisibility" -> profile.directoryVisibility();
            default -> null;
        };
    }

    private static String objectString(Object value, String field, int max) {
        if (value == null) return null;
        if (!(value instanceof String text) || text.trim().length() > max) throw invalid(field, "Giá trị không hợp lệ hoặc vượt quá độ dài cho phép.");
        return text;
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw invalid(field, "Trường bắt buộc, tối đa " + max + " ký tự.");
        return value.trim();
    }

    private static String optional(String value, String field, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw invalid(field, "Giá trị vượt quá " + max + " ký tự.");
        return normalized.isEmpty() ? null : normalized;
    }

    private static String email(String value) {
        String normalized = optional(value, "personalEmail", 254);
        if (normalized != null && !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw invalid("personalEmail", "Email liên hệ không đúng định dạng.");
        return normalized;
    }

    private static String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!EMPLOYMENT_STATUSES.contains(normalized)) throw invalid("employmentStatus", "Tình trạng việc làm không hợp lệ.");
        return normalized;
    }

    private static String normalizeVisibility(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!VISIBILITIES.contains(normalized)) throw invalid("directoryVisibility", "Quyền hiển thị không hợp lệ.");
        return normalized;
    }

    private static void validateOptionalId(Long id, String field) {
        if (id != null && id <= 0) throw invalid(field, "Giá trị phải là số nguyên dương.");
    }

    private static boolean isAdmin(AccountPrincipal principal) { return principal.roles().contains("ADMIN"); }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("page", "page phải từ 0; size từ 1 đến 100.");
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + ": " + message);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ALUMNI_PROFILE_NOT_FOUND", "Không tìm thấy hồ sơ trong phạm vi được phép.");
    }
}
