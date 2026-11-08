package vn.edu.phenikaa.alumni.profile;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.api.AuthDtos;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Statement;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

@Service
public class AlumniProfileService {
    private static final Set<String> CHANGE_FIELDS = Set.of(
            "studentCode", "fullName", "facultyId", "majorId", "cohortId", "graduationYear");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AlumniProfileService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public AuthDtos.ProfileResponse getProfile(long accountId) {
        List<AuthDtos.ProfileResponse> profiles = jdbc.query("""
                SELECT p.id, p.student_code, p.full_name, p.faculty_id, f.name AS faculty_name,
                       p.major_id, m.name AS major_name, p.cohort_id, c.code AS cohort_code,
                       p.graduation_year, p.personal_email, p.phone, p.employment_status,
                       p.employer_name, p.job_title, p.directory_visibility, p.verification_status,
                       p.verified_at, p.updated_at,
                       (SELECT pf.id FROM private_files pf
                        WHERE pf.owner_account_id = p.user_id AND pf.purpose = 'AVATAR'
                        ORDER BY pf.id DESC LIMIT 1) AS avatar_file_id
                FROM alumni_profiles p
                JOIN faculties f ON f.id = p.faculty_id
                LEFT JOIN majors m ON m.id = p.major_id
                JOIN cohorts c ON c.id = p.cohort_id
                WHERE p.user_id = ? AND p.archived_at IS NULL
                """, (rs, rowNum) -> new AuthDtos.ProfileResponse(
                rs.getLong("id"), rs.getString("student_code"), rs.getString("full_name"),
                rs.getLong("faculty_id"), rs.getString("faculty_name"),
                nullableLong(rs.getObject("major_id")), rs.getString("major_name"),
                rs.getLong("cohort_id"), rs.getString("cohort_code"), rs.getInt("graduation_year"),
                rs.getString("personal_email"), rs.getString("phone"), rs.getString("employment_status"),
                rs.getString("employer_name"), rs.getString("job_title"), rs.getString("directory_visibility"),
                rs.getString("verification_status"), instant(rs.getTimestamp("verified_at")),
                instant(rs.getTimestamp("updated_at")), nullableLong(rs.getObject("avatar_file_id"))), accountId);
        if (profiles.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ALUMNI_PROFILE_NOT_FOUND",
                "Không tìm thấy hồ sơ cựu sinh viên đang hoạt động.");
        return profiles.get(0);
    }

    @Transactional
    public AuthDtos.ProfileResponse updateProfile(long accountId, Map<String, Object> request) {
        getProfile(accountId);
        Set<String> allowed = Set.of("personalEmail", "phone", "directoryVisibility");
        if (!allowed.containsAll(request.keySet())) {
            throw validation("profile", "Chỉ được thay đổi email liên hệ, số điện thoại và quyền hiển thị.");
        }
        List<String> changedFields = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE alumni_profiles SET ");
        if (request.containsKey("personalEmail")) {
            String email = optionalString(request.get("personalEmail"), "personalEmail", 254);
            if (email != null && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
                throw validation("personalEmail", "Email liên hệ không đúng định dạng.");
            appendUpdate(sql, parameters, changedFields, "personal_email", "personalEmail", email);
        }
        if (request.containsKey("phone")) {
            appendUpdate(sql, parameters, changedFields, "phone", "phone", optionalString(request.get("phone"), "phone", 32));
        }
        if (request.containsKey("directoryVisibility")) {
            String visibility = optionalString(request.get("directoryVisibility"), "directoryVisibility", 40);
            if (visibility == null || !Set.of("PRIVATE", "VERIFIED_USERS").contains(visibility))
                throw validation("directoryVisibility", "Giá trị hiển thị không hợp lệ.");
            appendUpdate(sql, parameters, changedFields, "directory_visibility", "directoryVisibility", visibility);
        }
        if (changedFields.isEmpty()) return getProfile(accountId);
        sql.append("updated_at = CURRENT_TIMESTAMP(6) WHERE user_id = ? AND archived_at IS NULL");
        parameters.add(accountId);
        jdbc.update(sql.toString(), parameters.toArray());
        writeAudit(accountId, "ALUMNI_PROFILE_UPDATED", "ALUMNI_PROFILE", profileId(accountId), changedFields);
        return getProfile(accountId);
    }

    @Transactional
    public long submitChangeRequest(long accountId, AuthDtos.ProfileChangeRequest request) {
        getProfile(accountId);
        long profileId = lockProfile(accountId);
        Map<String, Object> proposed = validateChangeValues(request.proposedValues());
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM profile_change_requests pcr
                JOIN alumni_profiles ap ON ap.id = pcr.alumni_profile_id
                WHERE ap.user_id = ? AND pcr.status = 'PENDING'
                """, Integer.class, accountId);
        if (pending != null && pending > 0) throw new ApiException(HttpStatus.CONFLICT,
                "PROFILE_CHANGE_ALREADY_PENDING", "Đã có yêu cầu thay đổi đang chờ xử lý.");

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO profile_change_requests (alumni_profile_id, submitted_by, proposed_values)
                    VALUES (?, ?, CAST(? AS JSON))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, profileId);
            statement.setLong(2, accountId);
            statement.setString(3, json(proposed));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return a profile request id");
        long requestId = key.longValue();
        writeAudit(accountId, "ALUMNI_PROFILE_CHANGE_SUBMITTED", "PROFILE_CHANGE_REQUEST", requestId,
                new ArrayList<>(proposed.keySet()));
        return requestId;
    }

    public List<AuthDtos.ProfileChangeResponse> getMyChangeRequests(long accountId) {
        getProfile(accountId);
        return jdbc.query("""
                SELECT pcr.id, CAST(pcr.proposed_values AS CHAR) AS proposed_values,
                       pcr.status, pcr.review_note, pcr.submitted_at, pcr.reviewed_at
                FROM profile_change_requests pcr
                JOIN alumni_profiles ap ON ap.id = pcr.alumni_profile_id
                WHERE ap.user_id = ?
                ORDER BY pcr.submitted_at DESC LIMIT 20
                """, (rs, rowNum) -> new AuthDtos.ProfileChangeResponse(
                rs.getLong("id"), readJsonMap(rs.getString("proposed_values")), rs.getString("status"),
                rs.getString("review_note"), instant(rs.getTimestamp("submitted_at")),
                instant(rs.getTimestamp("reviewed_at"))), accountId);
    }

    private Map<String, Object> validateChangeValues(Map<String, Object> values) {
        if (values == null || values.isEmpty()) throw validation("proposedValues", "Cần có ít nhất một trường thay đổi.");
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            if (!CHANGE_FIELDS.contains(field)) throw validation("proposedValues", "Có trường không được phép thay đổi.");
            Object normalized = value;
            switch (field) {
                case "studentCode" -> {
                    if (!(value instanceof String text) || text.isBlank() || text.trim().length() > 32)
                        throw validation(field, "Mã sinh viên phải có từ 1 đến 32 ký tự.");
                    normalized = text.trim();
                }
                case "fullName" -> {
                    if (!(value instanceof String text) || text.isBlank() || text.trim().length() > 180)
                        throw validation(field, "Họ tên phải có từ 1 đến 180 ký tự.");
                    normalized = text.trim();
                }
                case "facultyId", "cohortId" -> normalized = positiveLong(field, value);
                case "majorId" -> normalized = value == null ? null : positiveLong(field, value);
                case "graduationYear" -> {
                    long year = positiveLong(field, value);
                    if (year < 1900 || year > 2200) throw validation(field, "Năm tốt nghiệp phải nằm trong 1900–2200.");
                    normalized = year;
                }
                default -> throw validation("proposedValues", "Có trường không được phép thay đổi.");
            }
            result.put(field, normalized);
        });
        return result;
    }

    private long positiveLong(String field, Object value) {
        if (!(value instanceof Number number) || number.longValue() <= 0
                || (number instanceof Double decimal && decimal != number.longValue())
                || (number instanceof Float decimal && decimal != number.longValue()))
            throw validation(field, "Giá trị phải là số nguyên dương.");
        return number.longValue();
    }

    private static String optionalString(Object value, String field, int maxLength) {
        if (value == null) return null;
        if (!(value instanceof String text) || text.trim().length() > maxLength)
            throw validation(field, "Giá trị không đúng định dạng hoặc vượt quá độ dài cho phép.");
        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void appendUpdate(StringBuilder sql, List<Object> parameters, List<String> fields,
                                     String column, String field, Object value) {
        sql.append(column).append(" = ?, ");
        parameters.add(value);
        fields.add(field);
    }

    private long profileId(long accountId) {
        Long id = jdbc.queryForObject("SELECT id FROM alumni_profiles WHERE user_id = ? AND archived_at IS NULL",
                Long.class, accountId);
        return id == null ? 0 : id;
    }

    private long lockProfile(long accountId) {
        Long id = jdbc.queryForObject("""
                SELECT id FROM alumni_profiles WHERE user_id = ? AND archived_at IS NULL FOR UPDATE
                """, Long.class, accountId);
        return id == null ? 0 : id;
    }

    private void writeAudit(long actorId, String action, String entityType, long entityId, List<String> fields) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields)
                VALUES (?, ?, ?, ?, CAST(? AS JSON))
                """, actorId, action, entityType, Long.toString(entityId), json(fields));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not serialize profile data", exception); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String value) {
        try { return objectMapper.readValue(value, Map.class); }
        catch (JacksonException exception) { return Collections.emptyMap(); }
    }

    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

    private static Long nullableLong(Object value) { return value instanceof Number number ? number.longValue() : null; }

    private static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + ": " + message);
    }
}
