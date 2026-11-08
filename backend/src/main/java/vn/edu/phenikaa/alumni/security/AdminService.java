package vn.edu.phenikaa.alumni.security;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.phenikaa.alumni.api.AdminDtos;
import vn.edu.phenikaa.alumni.api.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AdminService {
    private static final Set<String> ALLOWED_ROLES = Set.of("ALUMNI", "STUDENT", "EMPLOYER", "FACULTY_STAFF", "ADMIN");
    private static final Set<String> ALLOWED_PERMISSIONS = Set.of("EMPLOYER_REVIEW", "JOB_REVIEW", "ALUMNI_IMPORT");
    private static final Set<String> ALLOWED_STATUSES = Set.of("PENDING_EMAIL", "ACTIVE", "LOCKED", "DISABLED");

    private final JdbcTemplate jdbc;
    private final SessionRegistry sessionRegistry;

    public AdminService(JdbcTemplate jdbc, SessionRegistry sessionRegistry) {
        this.jdbc = jdbc;
        this.sessionRegistry = sessionRegistry;
    }

    public List<AdminDtos.AccountSummary> listAccounts(String q) {
        String filter = q == null ? "" : q.trim();
        List<Long> ids = jdbc.query("""
                SELECT id FROM user_accounts
                WHERE (? = '' OR email LIKE ? OR full_name LIKE ?)
                ORDER BY created_at DESC LIMIT 200
                """, (rs, rowNum) -> rs.getLong(1), filter, "%" + filter + "%", "%" + filter + "%");
        return ids.stream().map(this::getAccount).toList();
    }

    public List<AdminDtos.AlumniPromotionCandidate> listAlumniPromotionCandidates() {
        return jdbc.query("""
                SELECT p.id, p.student_code, p.full_name, f.name AS faculty_name, c.code AS cohort_code
                FROM alumni_profiles p
                JOIN faculties f ON f.id = p.faculty_id
                JOIN cohorts c ON c.id = p.cohort_id
                WHERE p.user_id IS NULL AND p.archived_at IS NULL AND p.verification_status = 'VERIFIED'
                ORDER BY p.full_name, p.student_code LIMIT 500
                """, (rs, rowNum) -> new AdminDtos.AlumniPromotionCandidate(
                rs.getLong("id"), rs.getString("student_code"), rs.getString("full_name"),
                rs.getString("faculty_name"), rs.getString("cohort_code")));
    }

    @Transactional
    public AdminDtos.AccountSummary promoteStudentToAlumni(long accountId, long alumniProfileId,
                                                            boolean confirmIdentityMatch, long actorId) {
        if (!confirmIdentityMatch)
            throw bad("IDENTITY_MATCH_CONFIRMATION_REQUIRED", "ADMIN phải xác nhận đã đối chiếu danh tính với nguồn chính thức trước khi liên kết hồ sơ.");
        List<String> statuses = jdbc.query("SELECT account_status FROM user_accounts WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), accountId);
        if (statuses.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản.");
        if (!"ACTIVE".equals(statuses.get(0)))
            throw bad("STUDENT_ACCOUNT_NOT_ACTIVE", "Chỉ tài khoản sinh viên đang hoạt động mới được chuyển vai trò.");

        List<String> currentRoles = jdbc.query("""
                SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = ? FOR UPDATE
                """, (rs, rowNum) -> rs.getString(1), accountId);
        if (!currentRoles.contains("STUDENT") || currentRoles.contains("ALUMNI"))
            throw bad("STUDENT_ROLE_REQUIRED", "Tài khoản phải đang có vai trò STUDENT và chưa có vai trò ALUMNI.");

        List<Long> candidateIds = jdbc.query("""
                SELECT id FROM alumni_profiles
                WHERE id = ? AND user_id IS NULL AND archived_at IS NULL AND verification_status = 'VERIFIED'
                FOR UPDATE
                """, (rs, rowNum) -> rs.getLong(1), alumniProfileId);
        if (candidateIds.isEmpty())
            throw bad("VERIFIED_UNLINKED_ALUMNI_PROFILE_REQUIRED", "Cần chọn hồ sơ cựu sinh viên đã xác minh và chưa liên kết tài khoản.");

        int linked = jdbc.update("""
                UPDATE alumni_profiles SET user_id = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND user_id IS NULL AND archived_at IS NULL AND verification_status = 'VERIFIED'
                """, accountId, alumniProfileId);
        if (linked != 1) throw bad("ALUMNI_PROFILE_LINK_CONFLICT", "Hồ sơ đã được liên kết hoặc thay đổi trong lúc xử lý.");

        int assigned = jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'ALUMNI'", accountId);
        if (assigned != 1) throw new IllegalStateException("Required ALUMNI role is missing from the migrated database.");
        jdbc.update("DELETE ur FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND r.code = 'STUDENT'", accountId);
        audit(actorId, "STUDENT_PROMOTED_TO_ALUMNI", "USER_ACCOUNT", accountId);
        expireSessions(accountId);
        return getAccount(accountId);
    }

    @Transactional
    public AdminDtos.AccountSummary createAccount(AdminDtos.AccountCreateRequest request, long actorId) {
        String email = OtpChallengeService.normalizeEmail(request.email());
        String fullName = request.fullName().trim();
        List<String> roles = normalizeCodes(request.roles(), ALLOWED_ROLES, "roles");
        List<Long> facultyIds = normalizeIds(request.facultyIds());
        List<String> permissions = normalizeCodes(request.permissions(), ALLOWED_PERMISSIONS, "permissions");
        validateAssignments(roles, facultyIds, permissions, null, Set.of());
        if (roles.stream().anyMatch(role -> !Set.of("FACULTY_STAFF", "ADMIN").contains(role))) {
            throw bad("ROLE_PROVISIONING_NOT_ALLOWED", "Tài khoản tự đăng ký của cựu sinh viên, sinh viên và doanh nghiệp được tạo từ luồng đăng ký công khai.");
        }
        jdbc.update("INSERT INTO user_accounts (email, full_name, account_status) VALUES (?, ?, 'PENDING_EMAIL')",
                email, fullName);
        long id = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = ?", Long.class, email);
        replaceAssignments(id, roles, facultyIds, permissions, actorId);
        audit(actorId, "ACCOUNT_CREATED", "USER_ACCOUNT", id);
        return getAccount(id);
    }

    @Transactional
    public AdminDtos.AccountSummary updateAccount(long id, AdminDtos.AccountUpdateRequest request, long actorId) {
        if (id == actorId) throw bad("SELF_ADMIN_CHANGE_BLOCKED", "Không thể tự thay đổi vai trò hoặc trạng thái tài khoản đang sử dụng.");
        List<String> existing = jdbc.query("SELECT account_status FROM user_accounts WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), id);
        if (existing.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản.");
        Set<String> currentRoles = new java.util.HashSet<>(jdbc.query("SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?", (rs, rowNum) -> rs.getString(1), id));
        List<String> roles = normalizeCodes(request.roles(), ALLOWED_ROLES, "roles");
        List<Long> facultyIds = normalizeIds(request.facultyIds());
        List<String> permissions = normalizeCodes(request.permissions(), ALLOWED_PERMISSIONS, "permissions");
        validateAssignments(roles, facultyIds, permissions, id, currentRoles);
        String status = request.accountStatus() == null ? null : request.accountStatus().trim().toUpperCase(Locale.ROOT);
        if (status != null && !ALLOWED_STATUSES.contains(status)) throw bad("ACCOUNT_STATUS_INVALID", "Trạng thái tài khoản không hợp lệ.");
        if (status != null) {
            if (status.equals("PENDING_EMAIL") && !"PENDING_EMAIL".equals(existing.get(0)))
                throw bad("ACCOUNT_STATUS_TRANSITION_INVALID", "Không thể đưa tài khoản đã kích hoạt trở lại trạng thái chờ xác minh Email.");
            if (status.equals("ACTIVE")) {
                Integer ready = jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE id = ? AND password_hash IS NOT NULL AND email_verified_at IS NOT NULL", Integer.class, id);
                if (ready == null || ready == 0) throw bad("ACCOUNT_ACTIVATION_REQUIRED", "Tài khoản chỉ hoạt động sau khi hoàn tất xác minh Email và đặt mật khẩu.");
            }
            jdbc.update("UPDATE user_accounts SET account_status = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", status, id);
        } else jdbc.update("UPDATE user_accounts SET updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", id);
        replaceAssignments(id, roles, facultyIds, permissions, actorId);
        audit(actorId, "ACCOUNT_ACCESS_UPDATED", "USER_ACCOUNT", id);
        expireSessions(id);
        return getAccount(id);
    }

    private void validateAssignments(List<String> roles, List<Long> facultyIds, List<String> permissions,
                                     Long accountId, Set<String> currentRoles) {
        if (roles.isEmpty()) throw bad("ROLE_REQUIRED", "Tài khoản phải có ít nhất một vai trò.");
        if (!roles.contains("FACULTY_STAFF") && !roles.contains("ADMIN") && !permissions.isEmpty())
            throw bad("PERMISSION_ROLE_REQUIRED", "Quyền kiểm duyệt/nhập chỉ được gán cho cán bộ hoặc quản trị viên.");
        if (roles.contains("FACULTY_STAFF")) {
            if (facultyIds.isEmpty()) throw bad("FACULTY_SCOPE_REQUIRED", "Cán bộ phải được gán ít nhất một khoa.");
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM faculties WHERE is_active = TRUE AND id IN (" + placeholders(facultyIds.size()) + ")", Integer.class, facultyIds.toArray());
            if (count == null || count != facultyIds.size()) throw bad("FACULTY_SCOPE_INVALID", "Mỗi khoa được gán phải tồn tại và đang hoạt động.");
        } else if (!facultyIds.isEmpty()) {
            throw bad("FACULTY_SCOPE_NOT_ALLOWED", "Chỉ tài khoản có vai trò FACULTY_STAFF mới có phạm vi khoa.");
        }
        if (roles.contains("ALUMNI") && accountId != null && !currentRoles.contains("ALUMNI")) {
            Integer profile = jdbc.queryForObject("SELECT COUNT(*) FROM alumni_profiles WHERE user_id = ? AND archived_at IS NULL AND verification_status = 'VERIFIED'", Integer.class, accountId);
            if (profile == null || profile == 0) throw bad("ALUMNI_PROFILE_REQUIRED", "Vai trò ALUMNI cần hồ sơ đã xác minh đang liên kết với tài khoản.");
        }
        if (roles.contains("EMPLOYER") && accountId != null && !currentRoles.contains("EMPLOYER")) {
            Integer profile = jdbc.queryForObject("SELECT COUNT(*) FROM employer_profiles WHERE user_id = ?", Integer.class, accountId);
            if (profile == null || profile == 0) throw bad("EMPLOYER_PROFILE_REQUIRED", "Vai trò EMPLOYER cần hồ sơ doanh nghiệp.");
        }
    }

    private void replaceAssignments(long id, List<String> roles, List<Long> facultyIds,
                                    List<String> permissions, long actorId) {
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
        for (String role : roles) jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, role);
        jdbc.update("DELETE FROM staff_faculty_scopes WHERE user_id = ?", id);
        for (Long facultyId : facultyIds) jdbc.update("INSERT INTO staff_faculty_scopes (user_id, faculty_id, assigned_by) VALUES (?, ?, ?)", id, facultyId, actorId);
        jdbc.update("DELETE FROM user_permissions WHERE user_id = ?", id);
        for (String permission : permissions) jdbc.update("INSERT INTO user_permissions (user_id, permission_id) SELECT ?, id FROM permissions WHERE code = ?", id, permission);
    }

    private AdminDtos.AccountSummary getAccount(long id) {
        List<AdminDtos.AccountSummary> rows = jdbc.query("""
                SELECT id, email, full_name, account_status, email_verified_at IS NOT NULL AS email_verified
                FROM user_accounts WHERE id = ?
                """, (rs, rowNum) -> new AdminDtos.AccountSummary(rs.getLong("id"), rs.getString("email"),
                rs.getString("full_name"), rs.getString("account_status"), rs.getBoolean("email_verified"),
                jdbc.queryForList("SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ? ORDER BY r.code", String.class, id),
                jdbc.queryForList("SELECT p.code FROM user_permissions up JOIN permissions p ON p.id = up.permission_id WHERE up.user_id = ? ORDER BY p.code", String.class, id),
                jdbc.queryForList("SELECT faculty_id FROM staff_faculty_scopes WHERE user_id = ? ORDER BY faculty_id", Long.class, id)), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản.");
        return rows.get(0);
    }

    private void expireSessions(long id) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof AccountPrincipal account && account.id() == id) {
                for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) session.expireNow();
            }
        }
    }

    private void audit(long actorId, String action, String type, long id) {
        jdbc.update("INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id) VALUES (?, ?, ?, ?)", actorId, action, type, Long.toString(id));
    }

    private static List<String> normalizeCodes(List<String> source, Set<String> allowed, String field) {
        if (source == null) return List.of();
        List<String> result = source.stream().map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if (!allowed.containsAll(result)) throw bad("ASSIGNMENT_INVALID", "Vai trò hoặc quyền không hợp lệ trong " + field + ".");
        return result;
    }

    private static List<Long> normalizeIds(List<Long> source) {
        if (source == null) return List.of();
        List<Long> result = source.stream().distinct().toList();
        if (result.stream().anyMatch(id -> id == null || id <= 0)) throw bad("FACULTY_SCOPE_INVALID", "Mã khoa phải là số nguyên dương.");
        return result;
    }

    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
}
