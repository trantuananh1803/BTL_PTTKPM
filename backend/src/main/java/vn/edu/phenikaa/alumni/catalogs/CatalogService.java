package vn.edu.phenikaa.alumni.catalogs;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.phenikaa.alumni.api.AdminDtos;
import vn.edu.phenikaa.alumni.api.ApiException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class CatalogService {
    private static final Set<String> TYPES = Set.of("FACULTY", "MAJOR", "COHORT");
    private final JdbcTemplate jdbc;

    public CatalogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<AdminDtos.CatalogItem> list(String type) {
        return switch (normalizeType(type)) {
            case "FACULTY" -> jdbc.query("SELECT id, code, name, is_active FROM faculties ORDER BY name",
                    (rs, rowNum) -> new AdminDtos.CatalogItem("FACULTY", rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("is_active"), null, null, null));
            case "MAJOR" -> jdbc.query("SELECT id, faculty_id, code, name, is_active FROM majors ORDER BY name",
                    (rs, rowNum) -> new AdminDtos.CatalogItem("MAJOR", rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("is_active"), rs.getLong("faculty_id"), null, null));
            case "COHORT" -> jdbc.query("SELECT id, code, admission_year, expected_graduation_year, is_active FROM cohorts ORDER BY admission_year DESC",
                    (rs, rowNum) -> new AdminDtos.CatalogItem("COHORT", rs.getLong("id"), rs.getString("code"), rs.getString("code"), rs.getBoolean("is_active"), null, rs.getInt("admission_year"), rs.getInt("expected_graduation_year")));
            default -> List.of();
        };
    }

    @Transactional
    public AdminDtos.CatalogItem create(AdminDtos.CatalogChangeRequest request, long actorId) {
        String type = normalizeType(request.type());
        String code = normalizeCode(request.code());
        long id = switch (type) {
            case "FACULTY" -> {
                String name = requiredName(request.name());
                jdbc.update("INSERT INTO faculties (code, name) VALUES (?, ?)", code, name);
                yield lastId("faculties", code);
            }
            case "MAJOR" -> {
                String name = requiredName(request.name());
                long facultyId = requiredId(request.facultyId(), "facultyId");
                requireActiveFaculty(facultyId);
                jdbc.update("INSERT INTO majors (faculty_id, code, name) VALUES (?, ?, ?)", facultyId, code, name);
                yield lastId("majors", code);
            }
            case "COHORT" -> {
                int admission = requiredYear(request.admissionYear(), "admissionYear");
                int graduation = requiredYear(request.expectedGraduationYear(), "expectedGraduationYear");
                if (graduation < admission) throw invalid("Năm tốt nghiệp dự kiến không được trước năm nhập học.");
                jdbc.update("INSERT INTO cohorts (code, admission_year, expected_graduation_year) VALUES (?, ?, ?)", code, admission, graduation);
                yield lastId("cohorts", code);
            }
            default -> throw invalid("Loại danh mục không hợp lệ.");
        };
        audit(actorId, "CATALOG_CREATED", type, id);
        return find(type, id);
    }

    @Transactional
    public AdminDtos.CatalogItem update(String pathType, long id, AdminDtos.CatalogChangeRequest request, long actorId) {
        String type = normalizeType(pathType);
        if (request.type() != null && !normalizeType(request.type()).equals(type)) throw invalid("Loại danh mục không khớp với URL.");
        String code = request.code() == null ? null : normalizeCode(request.code());
        String name = request.name() == null ? null : requiredName(request.name());
        Boolean active = request.active();
        int changed = switch (type) {
            case "FACULTY" -> jdbc.update("UPDATE faculties SET code = COALESCE(?, code), name = COALESCE(?, name), is_active = COALESCE(?, is_active) WHERE id = ?", code, name, active, id);
            case "MAJOR" -> {
                if (request.facultyId() != null) {
                    requireActiveFaculty(request.facultyId());
                    Long currentFaculty = jdbc.queryForObject("SELECT faculty_id FROM majors WHERE id = ?", Long.class, id);
                    if (currentFaculty == null) yield 0;
                    Integer referenced = jdbc.queryForObject("SELECT COUNT(*) FROM alumni_profiles WHERE major_id = ?", Integer.class, id);
                    if (referenced != null && referenced > 0 && !request.facultyId().equals(currentFaculty)) throw invalid("Không thể chuyển ngành đã được hồ sơ tham chiếu sang khoa khác.");
                }
                yield jdbc.update("UPDATE majors SET code = COALESCE(?, code), name = COALESCE(?, name), faculty_id = COALESCE(?, faculty_id), is_active = COALESCE(?, is_active) WHERE id = ?", code, name, request.facultyId(), active, id);
            }
            case "COHORT" -> {
                Integer existingAdmission = jdbc.queryForObject("SELECT admission_year FROM cohorts WHERE id = ?", Integer.class, id);
                Integer existingGraduation = jdbc.queryForObject("SELECT expected_graduation_year FROM cohorts WHERE id = ?", Integer.class, id);
                if (existingAdmission == null) yield 0;
                int admission = request.admissionYear() == null ? existingAdmission : requiredYear(request.admissionYear(), "admissionYear");
                int graduation = request.expectedGraduationYear() == null ? existingGraduation : requiredYear(request.expectedGraduationYear(), "expectedGraduationYear");
                if (graduation < admission) throw invalid("Năm tốt nghiệp dự kiến không được trước năm nhập học.");
                yield jdbc.update("UPDATE cohorts SET code = COALESCE(?, code), admission_year = ?, expected_graduation_year = ?, is_active = COALESCE(?, is_active) WHERE id = ?", code, admission, graduation, active, id);
            }
            default -> 0;
        };
        if (changed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "CATALOG_NOT_FOUND", "Không tìm thấy danh mục.");
        audit(actorId, "CATALOG_UPDATED", type, id);
        return find(type, id);
    }

    private AdminDtos.CatalogItem find(String type, long id) {
        return list(type).stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CATALOG_NOT_FOUND", "Không tìm thấy danh mục."));
    }

    private long lastId(String table, String code) {
        Long id = jdbc.queryForObject("SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
        return id == null ? 0 : id;
    }

    private void requireActiveFaculty(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM faculties WHERE id = ? AND is_active = TRUE", Integer.class, id);
        if (count == null || count == 0) throw invalid("Khoa không tồn tại hoặc đã ngừng hoạt động.");
    }

    private void audit(long actor, String action, String type, long id) {
        jdbc.update("INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id) VALUES (?, ?, ?, ?)", actor, action, "CATALOG_" + type, Long.toString(id));
    }

    private static String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw invalid("Loại danh mục phải là FACULTY, MAJOR hoặc COHORT.");
        return type;
    }
    private static String normalizeCode(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 40) throw invalid("Mã danh mục bắt buộc và tối đa 40 ký tự.");
        return value.trim().toUpperCase(Locale.ROOT);
    }
    private static String requiredName(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 180) throw invalid("Tên danh mục bắt buộc và tối đa 180 ký tự.");
        return value.trim();
    }
    private static long requiredId(Long value, String field) {
        if (value == null || value <= 0) throw invalid(field + " phải là số nguyên dương.");
        return value;
    }
    private static int requiredYear(Integer value, String field) {
        if (value == null || value < 1900 || value > 2200) throw invalid(field + " phải nằm trong 1900–2200.");
        return value;
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
}
