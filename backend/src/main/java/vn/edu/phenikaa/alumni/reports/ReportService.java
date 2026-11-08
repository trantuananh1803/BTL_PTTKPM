package vn.edu.phenikaa.alumni.reports;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.api.ReportDtos;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ReportService {
    private static final Set<String> EMPLOYMENT_STATUSES =
            Set.of("EMPLOYED", "SEEKING", "STUDYING", "OTHER", "UNKNOWN");
    private final JdbcTemplate jdbc;

    public ReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ReportDtos.FilterOptions filterOptions(AccountPrincipal principal) {
        boolean admin = principal.roles().contains("ADMIN");
        List<ReportDtos.FacultyOption> faculties = admin
                ? jdbc.query("SELECT id, code, name FROM faculties WHERE is_active=TRUE ORDER BY name",
                    (rs, row) -> new ReportDtos.FacultyOption(rs.getLong("id"), rs.getString("code"), rs.getString("name")))
                : jdbc.query("""
                    SELECT f.id, f.code, f.name FROM faculties f
                    JOIN staff_faculty_scopes s ON s.faculty_id=f.id
                    WHERE s.user_id=? AND f.is_active=TRUE ORDER BY f.name
                    """, (rs, row) -> new ReportDtos.FacultyOption(rs.getLong("id"), rs.getString("code"), rs.getString("name")), principal.id());
        List<ReportDtos.MajorOption> majors = admin
                ? jdbc.query("SELECT id, faculty_id, code, name FROM majors WHERE is_active=TRUE ORDER BY name",
                    (rs, row) -> new ReportDtos.MajorOption(rs.getLong("id"), rs.getLong("faculty_id"), rs.getString("code"), rs.getString("name")))
                : jdbc.query("""
                    SELECT m.id, m.faculty_id, m.code, m.name FROM majors m
                    JOIN staff_faculty_scopes s ON s.faculty_id=m.faculty_id
                    WHERE s.user_id=? AND m.is_active=TRUE ORDER BY m.name
                    """, (rs, row) -> new ReportDtos.MajorOption(rs.getLong("id"), rs.getLong("faculty_id"), rs.getString("code"), rs.getString("name")), principal.id());
        List<ReportDtos.CohortOption> cohorts = jdbc.query("""
                SELECT id, code, admission_year, expected_graduation_year
                FROM cohorts WHERE is_active=TRUE ORDER BY admission_year DESC
                """, (rs, row) -> new ReportDtos.CohortOption(rs.getLong("id"), rs.getString("code"),
                rs.getInt("admission_year"), rs.getInt("expected_graduation_year")));
        return new ReportDtos.FilterOptions(faculties, majors, cohorts);
    }

    public ReportDtos.Summary summarize(ReportDtos.Filters requested, AccountPrincipal principal) {
        ReportDtos.Filters filters = normalize(requested);
        boolean admin = principal.roles().contains("ADMIN");
        if (filters.facultyId() != null && !isActive("faculties", filters.facultyId()))
            throw invalid("facultyId", "Khoa không tồn tại hoặc đã ngừng hoạt động.");
        if (filters.cohortId() != null && !isActive("cohorts", filters.cohortId()))
            throw invalid("cohortId", "Khóa học không tồn tại hoặc đã ngừng hoạt động.");
        if (filters.majorId() != null) {
            List<Long> facultyIds = jdbc.query("SELECT faculty_id FROM majors WHERE id=? AND is_active=TRUE",
                    (rs, row) -> rs.getLong(1), filters.majorId());
            if (facultyIds.isEmpty()) throw invalid("majorId", "Ngành không tồn tại hoặc đã ngừng hoạt động.");
            long majorFacultyId = facultyIds.get(0);
            if (filters.facultyId() != null && filters.facultyId() != majorFacultyId)
                throw invalid("majorId", "Ngành không thuộc khoa đã chọn.");
            if (!admin && !hasFacultyScope(principal.id(), majorFacultyId)) throw forbiddenScope();
        }
        if (filters.facultyId() != null && !admin && !hasFacultyScope(principal.id(), filters.facultyId()))
            throw forbiddenScope();

        List<String> predicates = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        predicates.add("p.archived_at IS NULL");
        if (!admin) {
            predicates.add("EXISTS (SELECT 1 FROM staff_faculty_scopes s WHERE s.user_id=? AND s.faculty_id=p.faculty_id)");
            args.add(principal.id());
        }
        if (filters.facultyId() != null) { predicates.add("p.faculty_id=?"); args.add(filters.facultyId()); }
        if (filters.majorId() != null) { predicates.add("p.major_id=?"); args.add(filters.majorId()); }
        if (filters.cohortId() != null) { predicates.add("p.cohort_id=?"); args.add(filters.cohortId()); }
        if (filters.graduationYear() != null) { predicates.add("p.graduation_year=?"); args.add(filters.graduationYear()); }
        if (filters.employmentStatus() != null) { predicates.add("p.employment_status=?"); args.add(filters.employmentStatus()); }
        String where = String.join(" AND ", predicates);
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) AS total_profiles,
                       COALESCE(SUM(CASE WHEN p.verification_status='VERIFIED' THEN 1 ELSE 0 END),0) AS verified_count,
                       COALESCE(SUM(CASE WHEN p.verification_status='PENDING' THEN 1 ELSE 0 END),0) AS pending_count,
                       COALESCE(SUM(CASE WHEN p.employment_status IS NULL OR p.employment_status='UNKNOWN' THEN 1 ELSE 0 END),0) AS unknown_count,
                       COALESCE(SUM(CASE WHEN p.employment_status IS NOT NULL AND p.employment_status<>'UNKNOWN' THEN 1 ELSE 0 END),0) AS reported_count
                FROM alumni_profiles p WHERE 
                """ + where, args.toArray());
        long total = number(totals, "total_profiles");
        long verified = number(totals, "verified_count");
        long pending = number(totals, "pending_count");
        long unknown = number(totals, "unknown_count");
        long reported = number(totals, "reported_count");
        List<Map<String, Object>> grouped = jdbc.queryForList("""
                SELECT COALESCE(p.employment_status,'UNKNOWN') AS status_code, COUNT(*) AS profile_count
                FROM alumni_profiles p WHERE 
                """ + where + " GROUP BY COALESCE(p.employment_status,'UNKNOWN')", args.toArray());
        Map<String, Long> counts = new java.util.HashMap<>();
        for (Map<String, Object> row : grouped)
            counts.put(String.valueOf(row.get("status_code")), number(row, "profile_count"));
        List<ReportDtos.EmploymentStatusMetric> employment = new ArrayList<>();
        for (String status : List.of("EMPLOYED", "SEEKING", "STUDYING", "OTHER", "UNKNOWN")) {
            long count = counts.getOrDefault(status, 0L);
            long denominator = "UNKNOWN".equals(status) ? total : reported;
            employment.add(new ReportDtos.EmploymentStatusMetric(status, count,
                    percentage(count, denominator), "UNKNOWN".equals(status) ? "totalProfiles" : "reportedCount"));
        }
        return new ReportDtos.Summary(java.time.Instant.now(),
                new ReportDtos.AppliedFilters(filters.facultyId(), filters.majorId(), filters.cohortId(),
                        filters.graduationYear(), filters.employmentStatus()),
                total, verified, pending, reported, unknown, percentage(reported, total), employment);
    }

    private boolean hasFacultyScope(long accountId, long facultyId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM staff_faculty_scopes WHERE user_id=? AND faculty_id=?",
                Integer.class, accountId, facultyId);
        return count != null && count > 0;
    }

    private boolean isActive(String tableName, long id) {
        // Table names are supplied only by the two fixed call sites above.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + tableName + " WHERE id=? AND is_active=TRUE",
                Integer.class, id);
        return count != null && count > 0;
    }

    private static ReportDtos.Filters normalize(ReportDtos.Filters requested) {
        if (requested == null) return new ReportDtos.Filters(null, null, null, null, null);
        if (requested.facultyId() != null && requested.facultyId() < 1) throw invalid("facultyId", "Mã khoa không hợp lệ.");
        if (requested.majorId() != null && requested.majorId() < 1) throw invalid("majorId", "Mã ngành không hợp lệ.");
        if (requested.cohortId() != null && requested.cohortId() < 1) throw invalid("cohortId", "Mã khóa không hợp lệ.");
        if (requested.graduationYear() != null && (requested.graduationYear() < 1900 || requested.graduationYear() > 2200))
            throw invalid("graduationYear", "Năm tốt nghiệp phải nằm trong khoảng 1900–2200.");
        String status = requested.employmentStatus() == null || requested.employmentStatus().isBlank()
                ? null : requested.employmentStatus().trim().toUpperCase(Locale.ROOT);
        if (status != null && !EMPLOYMENT_STATUSES.contains(status))
            throw invalid("employmentStatus", "Tình trạng việc làm không hợp lệ.");
        return new ReportDtos.Filters(requested.facultyId(), requested.majorId(), requested.cohortId(),
                requested.graduationYear(), status);
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static BigDecimal percentage(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + ": " + message);
    }

    private static ApiException forbiddenScope() {
        return new ApiException(HttpStatus.FORBIDDEN, "FACULTY_SCOPE_FORBIDDEN",
                "Bộ lọc nằm ngoài phạm vi khoa được phân công.");
    }
}
