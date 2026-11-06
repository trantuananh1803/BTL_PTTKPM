package vn.edu.phenikaa.alumni.review;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.phenikaa.alumni.api.AdminDtos;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ReviewService {
    private static final Set<String> TYPES = Set.of("ALUMNI", "ALUMNI_CHANGE", "EMPLOYER", "JOB");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReviewService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<AdminDtos.ReviewItem> queue(String requestedType, AccountPrincipal actor) {
        String type = normalizeType(requestedType);
        authorizeType(type, actor);
        return switch (type) {
            case "ALUMNI" -> alumniQueue(actor);
            case "ALUMNI_CHANGE" -> alumniChangeQueue(actor);
            case "EMPLOYER" -> employerQueue();
            case "JOB" -> jobQueue();
            default -> List.of();
        };
    }

    @Transactional
    public void decide(String requestedType, long id, AdminDtos.ReviewDecisionRequest request, AccountPrincipal actor) {
        String type = normalizeType(requestedType);
        authorizeType(type, actor);
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        String note = request.reviewNote() == null ? null : request.reviewNote().trim();
        if (note != null && note.isEmpty()) note = null;
        if (note != null && note.length() > 1000) throw invalid("Ghi chú tối đa 1000 ký tự.");
        Set<String> allowed = type.equals("ALUMNI")
                ? Set.of("APPROVE", "REQUEST_CORRECTION")
                : Set.of("APPROVE", "REJECT", "REQUEST_CORRECTION");
        if (type.equals("ALUMNI_CHANGE")) allowed = Set.of("APPROVE", "REJECT");
        if (!allowed.contains(decision)) throw invalid("Quyết định không phù hợp với loại hồ sơ.");
        if ((!decision.equals("APPROVE")) && (note == null || note.isBlank()))
            throw invalid("Cần nêu lý do khi từ chối hoặc yêu cầu bổ sung.");

        if (type.equals("ALUMNI_CHANGE")) {
            decideAlumniChange(id, decision, note, actor);
            return;
        }

        int changed;
        String action;
        if (type.equals("ALUMNI") || type.equals("ALUMNI_CHANGE")) {
            Long facultyId = findAlumniFaculty(id);
            if (facultyId == null) throw notFound();
            authorizeFaculty(facultyId, actor);
            boolean approve = decision.equals("APPROVE");
            changed = jdbc.update("""
                    UPDATE alumni_profiles SET verification_status = ?,
                        verified_at = CASE WHEN ? THEN CURRENT_TIMESTAMP(6) ELSE NULL END,
                        verification_reviewed_by = ?, verification_reviewed_at = CURRENT_TIMESTAMP(6),
                        verification_note = ?, updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = ? AND verification_status = 'PENDING' AND archived_at IS NULL
                      AND (user_id IS NULL OR EXISTS (
                          SELECT 1 FROM user_accounts ua
                          WHERE ua.id = alumni_profiles.user_id AND ua.account_status = 'ACTIVE'
                            AND ua.email_verified_at IS NOT NULL
                      ))
                    """, approve ? "VERIFIED" : "NEEDS_CORRECTION", approve, actor.id(), note, id);
            action = approve ? "ALUMNI_VERIFICATION_APPROVED" : "ALUMNI_VERIFICATION_CORRECTION_REQUESTED";
        } else if (type.equals("EMPLOYER")) {
            String status = statusFor(decision, "APPROVED");
            changed = jdbc.update("""
                    UPDATE employer_profiles ep
                    JOIN user_accounts ua ON ua.id = ep.user_id
                    SET ep.review_status = ?, ep.reviewed_by = ?,
                        ep.reviewed_at = CURRENT_TIMESTAMP(6), ep.review_note = ?, ep.updated_at = CURRENT_TIMESTAMP(6)
                    WHERE ep.id = ? AND ep.review_status = 'PENDING'
                      AND ua.account_status = 'ACTIVE' AND ua.email_verified_at IS NOT NULL
                    """, status, actor.id(), note, id);
            action = "EMPLOYER_REVIEW_" + status;
        } else {
            String status = statusFor(decision, "PUBLISHED");
            changed = jdbc.update("""
                    UPDATE job_postings jp
                    JOIN user_accounts ua ON ua.id = jp.employer_user_id
                    JOIN employer_profiles ep ON ep.user_id = jp.employer_user_id
                    SET jp.status = ?, jp.reviewed_by = ?,
                        jp.reviewed_at = CURRENT_TIMESTAMP(6), jp.review_note = ?, jp.updated_at = CURRENT_TIMESTAMP(6)
                    WHERE jp.id = ? AND jp.status = 'PENDING_REVIEW'
                      AND ua.account_status = 'ACTIVE' AND ua.email_verified_at IS NOT NULL
                      AND ep.review_status = 'APPROVED'
                    """, status, actor.id(), note, id);
            action = "JOB_REVIEW_" + status;
        }
        if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "REVIEW_ITEM_NOT_PENDING", "Hồ sơ không còn ở trạng thái chờ duyệt.");
        if (actor.roles().contains("ADMIN")) action = "ADMIN_INTERVENTION_" + action;
        jdbc.update("INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id) VALUES (?, ?, ?, ?)",
                actor.id(), action, type, Long.toString(id));
    }

    private List<AdminDtos.ReviewItem> alumniQueue(AccountPrincipal actor) {
        String scope = actor.roles().contains("ADMIN") ? "" : " AND EXISTS (SELECT 1 FROM staff_faculty_scopes sfs WHERE sfs.user_id = ? AND sfs.faculty_id = ap.faculty_id)";
        Object[] params = actor.roles().contains("ADMIN") ? new Object[]{} : new Object[]{actor.id()};
        return jdbc.query("""
                SELECT ap.id, ap.verification_status AS status, ap.full_name, ua.email,
                       ap.student_code, f.name AS faculty_name, m.name AS major_name,
                       c.code AS cohort_code, ap.graduation_year, ap.created_at, ap.verification_note
                FROM alumni_profiles ap
                JOIN faculties f ON f.id = ap.faculty_id
                LEFT JOIN majors m ON m.id = ap.major_id
                JOIN cohorts c ON c.id = ap.cohort_id
                LEFT JOIN user_accounts ua ON ua.id = ap.user_id
                WHERE ap.verification_status = 'PENDING' AND ap.archived_at IS NULL
                  AND (ap.user_id IS NULL OR (ua.account_status = 'ACTIVE' AND ua.email_verified_at IS NOT NULL))
                """ + scope + " ORDER BY ap.created_at ASC LIMIT 200", (rs, rowNum) -> new AdminDtos.ReviewItem(
                "ALUMNI", rs.getLong("id"), rs.getString("status"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("student_code"), rs.getString("faculty_name"),
                rs.getString("major_name"), rs.getString("cohort_code"), nullableInt(rs.getObject("graduation_year")),
                null, null, null, null, null, instant(rs.getTimestamp("created_at")), rs.getString("verification_note"), null), params);
    }

    private List<AdminDtos.ReviewItem> alumniChangeQueue(AccountPrincipal actor) {
        String scope = actor.roles().contains("ADMIN") ? "" : " AND EXISTS (SELECT 1 FROM staff_faculty_scopes sfs WHERE sfs.user_id = ? AND sfs.faculty_id = ap.faculty_id)";
        Object[] params = actor.roles().contains("ADMIN") ? new Object[]{} : new Object[]{actor.id()};
        return jdbc.query("""
                SELECT pcr.id, pcr.status, ap.full_name, ua.email, ap.student_code,
                       f.name AS faculty_name, m.name AS major_name, c.code AS cohort_code,
                       ap.graduation_year, pcr.submitted_at, pcr.review_note,
                       CAST(pcr.proposed_values AS CHAR) AS proposed_values
                FROM profile_change_requests pcr
                JOIN alumni_profiles ap ON ap.id = pcr.alumni_profile_id
                JOIN user_accounts ua ON ua.id = ap.user_id AND ua.id = pcr.submitted_by
                JOIN faculties f ON f.id = ap.faculty_id
                LEFT JOIN majors m ON m.id = ap.major_id
                JOIN cohorts c ON c.id = ap.cohort_id
                WHERE pcr.status = 'PENDING' AND ap.archived_at IS NULL
                  AND ua.account_status = 'ACTIVE' AND ua.email_verified_at IS NOT NULL
                """ + scope + " ORDER BY pcr.submitted_at ASC LIMIT 200", (rs, rowNum) -> {
            Map<String, Object> proposed = readJsonMap(rs.getString("proposed_values"));
            return new AdminDtos.ReviewItem("ALUMNI_CHANGE", rs.getLong("id"), rs.getString("status"),
                    rs.getString("full_name"), rs.getString("email"), rs.getString("student_code"),
                    rs.getString("faculty_name"), rs.getString("major_name"), rs.getString("cohort_code"),
                    nullableInt(rs.getObject("graduation_year")), null, null, null, null, null,
                    instant(rs.getTimestamp("submitted_at")), rs.getString("review_note"),
                    displayProposedValues(proposed));
        }, params);
    }

    private List<AdminDtos.ReviewItem> employerQueue() {
        return jdbc.query("""
                SELECT ep.id, ep.review_status AS status, ep.organization_name, ua.email,
                       ep.business_registration_number, ep.website, ep.created_at, ep.review_note
                FROM employer_profiles ep JOIN user_accounts ua ON ua.id = ep.user_id
                WHERE ep.review_status = 'PENDING'
                  AND ua.account_status = 'ACTIVE' AND ua.email_verified_at IS NOT NULL
                ORDER BY ep.created_at ASC LIMIT 200
                """, (rs, rowNum) -> new AdminDtos.ReviewItem("EMPLOYER", rs.getLong("id"),
                rs.getString("status"), rs.getString("organization_name"), rs.getString("email"), null,
                null, null, null, null, rs.getString("organization_name"),
                rs.getString("business_registration_number"), rs.getString("website"), null, null,
                instant(rs.getTimestamp("created_at")), rs.getString("review_note"), null));
    }

    private List<AdminDtos.ReviewItem> jobQueue() {
        return jdbc.query("""
                SELECT jp.id, jp.status, jp.title, jp.description, ua.email, ep.organization_name,
                       jp.created_at, jp.review_note
                FROM job_postings jp
                JOIN user_accounts ua ON ua.id = jp.employer_user_id
                JOIN employer_profiles ep ON ep.user_id = jp.employer_user_id
                WHERE jp.status = 'PENDING_REVIEW' AND ep.review_status = 'APPROVED'
                  AND ua.account_status = 'ACTIVE' AND ua.email_verified_at IS NOT NULL
                ORDER BY jp.created_at ASC LIMIT 200
                """, (rs, rowNum) -> new AdminDtos.ReviewItem("JOB", rs.getLong("id"),
                rs.getString("status"), rs.getString("title"), rs.getString("email"), null,
                null, null, null, null, rs.getString("organization_name"), null,
                null, rs.getString("title"), rs.getString("description"),
                instant(rs.getTimestamp("created_at")), rs.getString("review_note"), null));
    }

    private void decideAlumniChange(long requestId, String decision, String note, AccountPrincipal actor) {
        List<ChangeRequestRow> rows = jdbc.query("""
                SELECT pcr.id, pcr.alumni_profile_id, pcr.submitted_by,
                       CAST(pcr.proposed_values AS CHAR) AS proposed_values,
                       ap.user_id, ap.faculty_id, ap.major_id, ap.cohort_id,
                       ap.graduation_year, ap.student_code
                FROM profile_change_requests pcr
                JOIN alumni_profiles ap ON ap.id = pcr.alumni_profile_id
                JOIN user_accounts ua ON ua.id = ap.user_id
                WHERE pcr.id = ? AND pcr.status = 'PENDING'
                  AND ap.archived_at IS NULL
                  AND ua.account_status = 'ACTIVE' AND ua.email_verified_at IS NOT NULL
                FOR UPDATE
                """, (rs, rowNum) -> new ChangeRequestRow(rs.getLong("id"), rs.getLong("alumni_profile_id"),
                rs.getLong("submitted_by"), rs.getLong("user_id"), rs.getLong("faculty_id"),
                nullableLong(rs.getObject("major_id")), rs.getLong("cohort_id"),
                nullableInt(rs.getObject("graduation_year")), rs.getString("student_code"),
                rs.getString("proposed_values")), requestId);
        if (rows.isEmpty()) throw notFound();
        ChangeRequestRow row = rows.get(0);
        if (row.submitterId() != row.profileOwnerId()) throw notFound();
        authorizeFaculty(row.facultyId(), actor);

        Map<String, Object> changes = readJsonMap(row.proposedValues());
        if (changes.isEmpty()) throw invalid("Yêu cầu không có nội dung hợp lệ.");
        if (decision.equals("APPROVE")) applyAlumniChanges(row, changes);

        String status = decision.equals("APPROVE") ? "APPROVED" : "REJECTED";
        int changed = jdbc.update("""
                UPDATE profile_change_requests
                SET status = ?, reviewed_by = ?, review_note = ?, reviewed_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = 'PENDING'
                """, status, actor.id(), note, requestId);
        if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "REVIEW_ITEM_NOT_PENDING",
                "Yêu cầu không còn ở trạng thái chờ xử lý.");

        String action = "ALUMNI_PROFILE_CHANGE_" + status;
        if (actor.roles().contains("ADMIN")) action = "ADMIN_INTERVENTION_" + action;
        jdbc.update("""
                INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields)
                VALUES (?, ?, 'PROFILE_CHANGE_REQUEST', ?, CAST(? AS JSON))
                """, actor.id(), action, Long.toString(requestId), json(changes.keySet().stream().toList()));
        String message = decision.equals("APPROVE")
                ? "Yêu cầu thay đổi hồ sơ học vụ của bạn đã được chấp thuận."
                : "Yêu cầu thay đổi hồ sơ học vụ của bạn bị từ chối: " + note;
        jdbc.update("INSERT INTO notifications (recipient_account_id, title, body) VALUES (?, ?, ?)",
                row.profileOwnerId(), "Kết quả xét duyệt hồ sơ", message);
    }

    private void applyAlumniChanges(ChangeRequestRow row, Map<String, Object> changes) {
        Set<String> allowedFields = Set.of("studentCode", "fullName", "facultyId", "majorId", "cohortId", "graduationYear");
        if (!allowedFields.containsAll(changes.keySet())) throw invalid("Yêu cầu chứa trường không được phép.");

        long facultyId = changes.containsKey("facultyId") ? positiveLong(changes.get("facultyId")) : row.facultyId();
        Long majorId = changes.containsKey("majorId")
                ? (changes.get("majorId") == null ? null : positiveLong(changes.get("majorId"))) : row.majorId();
        long cohortId = changes.containsKey("cohortId") ? positiveLong(changes.get("cohortId")) : row.cohortId();
        Integer graduationYear = changes.containsKey("graduationYear")
                ? boundedYear(changes.get("graduationYear")) : row.graduationYear();

        if (changes.containsKey("facultyId") || changes.containsKey("majorId") || changes.containsKey("cohortId")) {
            requireActive("SELECT COUNT(*) FROM faculties WHERE id = ? AND is_active = TRUE", facultyId);
            requireActive("SELECT COUNT(*) FROM cohorts WHERE id = ? AND is_active = TRUE", cohortId);
            if (majorId != null) {
                Integer validMajor = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM majors WHERE id = ? AND faculty_id = ? AND is_active = TRUE
                        """, Integer.class, majorId, facultyId);
                if (validMajor == null || validMajor == 0)
                    throw invalid("Ngành phải đang hoạt động và thuộc khoa được chọn.");
            }
        }
        if (changes.containsKey("studentCode")) {
            Object value = changes.get("studentCode");
            if (!(value instanceof String text) || text.trim().isEmpty() || text.trim().length() > 32)
                throw invalid("Mã sinh viên phải có từ 1 đến 32 ký tự.");
            Integer duplicate = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM alumni_profiles WHERE student_code = ? AND id <> ?",
                    Integer.class, text.trim(), row.profileId());
            if (duplicate != null && duplicate > 0)
                throw new ApiException(HttpStatus.CONFLICT, "STUDENT_CODE_ALREADY_EXISTS", "Mã sinh viên đã được sử dụng.");
        }
        if (changes.containsKey("fullName")) {
            Object value = changes.get("fullName");
            if (!(value instanceof String text) || text.trim().isEmpty() || text.trim().length() > 180)
                throw invalid("Họ tên phải có từ 1 đến 180 ký tự.");
        }

        StringBuilder sql = new StringBuilder("UPDATE alumni_profiles SET ");
        List<Object> args = new ArrayList<>();
        if (changes.containsKey("studentCode")) { sql.append("student_code = ?, "); args.add(((String) changes.get("studentCode")).trim()); }
        if (changes.containsKey("fullName")) { sql.append("full_name = ?, "); args.add(((String) changes.get("fullName")).trim()); }
        if (changes.containsKey("facultyId")) { sql.append("faculty_id = ?, "); args.add(facultyId); }
        if (changes.containsKey("majorId")) { sql.append("major_id = ?, "); args.add(majorId); }
        if (changes.containsKey("cohortId")) { sql.append("cohort_id = ?, "); args.add(cohortId); }
        if (changes.containsKey("graduationYear")) { sql.append("graduation_year = ?, "); args.add(graduationYear); }
        sql.append("updated_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND archived_at IS NULL");
        args.add(row.profileId());
        jdbc.update(sql.toString(), args.toArray());
    }

    private void requireActive(String query, long id) {
        Integer count = jdbc.queryForObject(query, Integer.class, id);
        if (count == null || count == 0) throw invalid("Khoa hoặc khóa được chọn không còn hoạt động.");
    }

    private Map<String, Object> displayProposedValues(Map<String, Object> proposed) {
        Map<String, Object> display = new LinkedHashMap<>(proposed);
        if (proposed.containsKey("facultyId"))
            display.put("facultyId", catalogValue("SELECT name FROM faculties WHERE id = ?", proposed.get("facultyId")));
        if (proposed.containsKey("majorId"))
            display.put("majorId", proposed.get("majorId") == null ? "Không chọn ngành"
                    : catalogValue("SELECT name FROM majors WHERE id = ?", proposed.get("majorId")));
        if (proposed.containsKey("cohortId"))
            display.put("cohortId", catalogValue("SELECT code FROM cohorts WHERE id = ?", proposed.get("cohortId")));
        return display;
    }

    private String catalogValue(String query, Object id) {
        List<String> values = jdbc.query(query, (rs, rowNum) -> rs.getString(1), id);
        return values.isEmpty() ? "Danh mục không còn tồn tại" : values.get(0);
    }

    private long positiveLong(Object value) {
        if (!(value instanceof Number number) || number.longValue() <= 0
                || number.doubleValue() != number.longValue())
            throw invalid("Giá trị danh mục phải là số nguyên dương.");
        return number.longValue();
    }

    private int boundedYear(Object value) {
        long year = positiveLong(value);
        if (year < 1900 || year > 2200) throw invalid("Năm tốt nghiệp phải nằm trong 1900–2200.");
        return Math.toIntExact(year);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String value) {
        try { return objectMapper.readValue(value, Map.class); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not parse review request", exception); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not serialize review fields", exception); }
    }

    private void authorizeType(String type, AccountPrincipal actor) {
        if (actor.roles().contains("ADMIN")) return;
        if (type.equals("ALUMNI") || type.equals("ALUMNI_CHANGE")) {
            if (!actor.roles().contains("FACULTY_STAFF")) throw forbidden();
            return;
        }
        String permission = type.equals("EMPLOYER") ? "EMPLOYER_REVIEW" : "JOB_REVIEW";
        if (!actor.permissions().contains(permission)) throw forbidden();
    }

    private void authorizeFaculty(long facultyId, AccountPrincipal actor) {
        if (actor.roles().contains("ADMIN")) return;
        Integer allowed = jdbc.queryForObject("SELECT COUNT(*) FROM staff_faculty_scopes WHERE user_id = ? AND faculty_id = ?", Integer.class, actor.id(), facultyId);
        if (allowed == null || allowed == 0) throw forbidden();
    }

    private Long findAlumniFaculty(long id) {
        List<Long> values = jdbc.query("SELECT faculty_id FROM alumni_profiles WHERE id = ? AND archived_at IS NULL AND verification_status = 'PENDING'", (rs, rowNum) -> rs.getLong(1), id);
        return values.isEmpty() ? null : values.get(0);
    }

    private static String statusFor(String decision, String approvedStatus) {
        return switch (decision) {
            case "APPROVE" -> approvedStatus;
            case "REJECT" -> "REJECTED";
            case "REQUEST_CORRECTION" -> "NEEDS_CORRECTION";
            default -> throw invalid("Quyết định không hợp lệ.");
        };
    }

    private static String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw invalid("Loại hàng chờ không hợp lệ.");
        return type;
    }
    private static Integer nullableInt(Object value) { return value instanceof Number number ? number.intValue() : null; }
    private static Long nullableLong(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền xử lý chức năng này hoặc hồ sơ ngoài phạm vi được giao."); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "REVIEW_ITEM_NOT_FOUND", "Không tìm thấy hồ sơ đang chờ duyệt."); }

    private record ChangeRequestRow(long requestId, long profileId, long submitterId, long profileOwnerId,
                                    long facultyId, Long majorId, long cohortId, Integer graduationYear,
                                    String studentCode, String proposedValues) {}
}
