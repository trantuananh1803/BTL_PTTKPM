package vn.edu.phenikaa.alumni.profile;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.api.EmploymentDtos;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class EmploymentService {
    private static final Set<String> STATUSES = Set.of("UNKNOWN", "EMPLOYED", "SEEKING", "STUDYING", "OTHER");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EmploymentService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public EmploymentDtos.EmploymentResponse get(long accountId) {
        List<EmploymentDtos.EmploymentResponse> rows = jdbc.query("""
                SELECT employment_status, employer_name, job_title, employment_industry, employment_updated_at
                FROM alumni_profiles WHERE user_id = ? AND archived_at IS NULL
                """, (rs, rowNum) -> new EmploymentDtos.EmploymentResponse(rs.getString("employment_status"),
                rs.getString("employer_name"), rs.getString("job_title"), rs.getString("employment_industry"),
                instant(rs.getTimestamp("employment_updated_at"))), accountId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ALUMNI_PROFILE_NOT_FOUND",
                "Không tìm thấy hồ sơ cựu sinh viên đang hoạt động.");
        return rows.get(0);
    }

    @Transactional
    public EmploymentDtos.EmploymentResponse update(long accountId, EmploymentDtos.EmploymentUpdateRequest request) {
        String status = request.employmentStatus().trim().toUpperCase(java.util.Locale.ROOT);
        if (!STATUSES.contains(status)) throw invalid("employmentStatus", "Tình trạng việc làm không hợp lệ.");
        String employer = optional(request.employerName(), "employerName");
        String title = optional(request.jobTitle(), "jobTitle");
        String industry = optional(request.industry(), "industry");
        EmploymentDtos.EmploymentResponse before = get(accountId);
        List<String> changed = new ArrayList<>();
        if (!status.equals(before.employmentStatus())) changed.add("employmentStatus");
        if (!java.util.Objects.equals(employer, before.employerName())) changed.add("employerName");
        if (!java.util.Objects.equals(title, before.jobTitle())) changed.add("jobTitle");
        if (!java.util.Objects.equals(industry, before.industry())) changed.add("industry");
        if (!changed.isEmpty()) {
            jdbc.update("""
                    UPDATE alumni_profiles SET employment_status = ?, employer_name = ?, job_title = ?,
                        employment_industry = ?, employment_updated_at = CURRENT_TIMESTAMP(6),
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE user_id = ? AND archived_at IS NULL
                    """, status, employer, title, industry, accountId);
            jdbc.update("""
                    INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields)
                    VALUES (?, 'EMPLOYMENT_SURVEY_UPDATED', 'ALUMNI_PROFILE',
                            (SELECT CAST(id AS CHAR) FROM alumni_profiles WHERE user_id = ? AND archived_at IS NULL),
                            CAST(? AS JSON))
                    """, accountId, accountId, toJsonArray(changed));
        }
        return get(accountId);
    }

    private static String optional(String value, String field) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > 180) throw invalid(field, "Giá trị vượt quá 180 ký tự.");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String toJsonArray(List<String> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not serialize audit fields", exception); }
    }

    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + ": " + message);
    }
}
