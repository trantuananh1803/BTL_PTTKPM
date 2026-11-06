package vn.edu.phenikaa.alumni.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.api.AuthDtos;

import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AuthService {
    public record RegistrationResult(OtpChallengeService.Delivery delivery, boolean duplicate) {}

    private static final Set<String> SELF_REGISTERABLE_ROLES = Set.of("ALUMNI", "STUDENT", "EMPLOYER");
    private static final String GENERIC_RECOVERY_MESSAGE =
            "Nếu thông tin phù hợp, hệ thống sẽ gửi hướng dẫn đến email đã đăng ký. Nếu chưa nhận được, hãy thử gửi lại sau.";

    private final JdbcTemplate jdbc;
    private final OtpChallengeService otp;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionRegistry sessionRegistry;

    public AuthService(JdbcTemplate jdbc, OtpChallengeService otp, PasswordEncoder passwordEncoder,
                       PasswordPolicy passwordPolicy, SessionRegistry sessionRegistry) {
        this.jdbc = jdbc;
        this.otp = otp;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessionRegistry = sessionRegistry;
    }

    @Transactional
    public RegistrationResult register(AuthDtos.RegisterRequest request, String requesterIp) {
        String role = request.role().trim().toUpperCase(Locale.ROOT);
        if (!SELF_REGISTERABLE_ROLES.contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_NOT_REGISTERABLE",
                    "Vai trò này không được đăng ký công khai.");
        }
        String email = OtpChallengeService.normalizeEmail(request.email());
        String fullName = request.fullName().trim();
        if (fullName.isEmpty()) throw validation("fullName", "Họ và tên không được để trống.");

        if (role.equals("ALUMNI")) validateAlumniRegistration(request);
        if (role.equals("EMPLOYER")) validateEmployerRegistration(request);

        ExistingAccount existing = findAccount(email);
        if (existing != null) {
            if ("PENDING_EMAIL".equals(existing.status()) && role.equals(existing.role())) {
                OtpChallengeService.Delivery result = otp.issue(existing.id(), email, requesterIp,
                        OtpChallengeService.Purpose.REGISTER);
                return new RegistrationResult(result, false);
            }
            return new RegistrationResult(OtpChallengeService.Delivery.NOT_SENT, true);
        }

        Long accountId;
        try {
            accountId = insertAccount(email, fullName);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?",
                    accountId, role);
            if (role.equals("ALUMNI")) insertAlumniProfile(accountId, request, fullName);
            if (role.equals("EMPLOYER")) insertEmployerProfile(accountId, request);
        } catch (DuplicateKeyException exception) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new RegistrationResult(OtpChallengeService.Delivery.NOT_SENT, true);
        }

        OtpChallengeService.Delivery delivery = otp.issue(accountId, email, requesterIp,
                OtpChallengeService.Purpose.REGISTER);
        return new RegistrationResult(delivery, false);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void completeRegistration(AuthDtos.CompleteRegistrationRequest request) {
        passwordPolicy.validate(request.password());
        OtpChallengeService.Verification verification = otp.verify(request.email(), request.otp(),
                OtpChallengeService.Purpose.REGISTER);
        String email = OtpChallengeService.normalizeEmail(request.email());
        int changed = jdbc.update("""
                UPDATE user_accounts SET account_status = 'ACTIVE', email_verified_at = CURRENT_TIMESTAMP(6),
                    password_hash = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND email = ? AND account_status = 'PENDING_EMAIL'
                """, passwordEncoder.encode(request.password()), verification.accountId(), email);
        if (changed != 1) throw invalidOtp();
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OtpChallengeService.Delivery resendRegistration(String email, String requesterIp) {
        String normalizedEmail = OtpChallengeService.normalizeEmail(email);
        List<Long> accounts = jdbc.query("""
                SELECT ua.id FROM user_accounts ua JOIN user_roles ur ON ur.user_id = ua.id
                JOIN roles r ON r.id = ur.role_id
                WHERE ua.email = ? AND ua.account_status = 'PENDING_EMAIL'
                  AND r.code IN ('ALUMNI', 'STUDENT', 'EMPLOYER')
                LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), normalizedEmail);
        return otp.issue(accounts.isEmpty() ? null : accounts.get(0), normalizedEmail, requesterIp,
                OtpChallengeService.Purpose.REGISTER);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OtpChallengeService.Delivery issueStaffActivationOtp(long accountId, String requesterIp) {
        List<ActivationTarget> targets = jdbc.query("""
                SELECT ua.email, ua.account_status,
                       SUM(CASE WHEN r.code IN ('FACULTY_STAFF', 'ADMIN') THEN 1 ELSE 0 END) AS managed_role_count
                FROM user_accounts ua
                LEFT JOIN user_roles ur ON ur.user_id = ua.id
                LEFT JOIN roles r ON r.id = ur.role_id
                WHERE ua.id = ? GROUP BY ua.id, ua.email, ua.account_status
                """, (rs, rowNum) -> new ActivationTarget(rs.getString("email"), rs.getString("account_status"),
                rs.getInt("managed_role_count")), accountId);
        if (targets.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy tài khoản.");
        ActivationTarget target = targets.get(0);
        if (!"PENDING_EMAIL".equals(target.status()) || target.managedRoleCount() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_PENDING_ACTIVATION",
                    "Chỉ tài khoản cán bộ/quản trị đang chờ xác minh Email mới nhận mã kích hoạt.");
        }
        return otp.issue(accountId, target.email(), requesterIp, OtpChallengeService.Purpose.REGISTER);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OtpChallengeService.Delivery requestRecovery(String email, String requesterIp) {
        String normalizedEmail = OtpChallengeService.normalizeEmail(email);
        Long accountId = findActiveAccountId(normalizedEmail);
        return otp.issue(accountId, normalizedEmail, requesterIp,
                OtpChallengeService.Purpose.PASSWORD_RECOVERY);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void completeRecovery(AuthDtos.CompleteRecoveryRequest request) {
        passwordPolicy.validate(request.newPassword());
        OtpChallengeService.Verification verification = otp.verify(request.email(), request.otp(),
                OtpChallengeService.Purpose.PASSWORD_RECOVERY);
        int changed = jdbc.update("""
                UPDATE user_accounts SET password_hash = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND account_status = 'ACTIVE'
                """, passwordEncoder.encode(request.newPassword()), verification.accountId());
        if (changed != 1) throw invalidOtp();
        expireSessions(verification.accountId());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OtpChallengeService.Delivery resendRecovery(String email, String requesterIp) {
        String normalizedEmail = OtpChallengeService.normalizeEmail(email);
        return otp.issue(findActiveAccountId(normalizedEmail), normalizedEmail, requesterIp,
                OtpChallengeService.Purpose.PASSWORD_RECOVERY);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void changePassword(long accountId, AuthDtos.ChangePasswordRequest request) {
        passwordPolicy.validate(request.newPassword());
        List<String> hashes = jdbc.query("""
                SELECT password_hash FROM user_accounts
                WHERE id = ? AND account_status = 'ACTIVE'
                """, (rs, rowNum) -> rs.getString(1), accountId);
        if (hashes.isEmpty() || hashes.get(0) == null
                || !passwordEncoder.matches(request.currentPassword(), hashes.get(0))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID",
                    "Mật khẩu hiện tại không đúng.");
        }
        jdbc.update("UPDATE user_accounts SET password_hash = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                passwordEncoder.encode(request.newPassword()), accountId);
        expireSessions(accountId);
    }

    public AuthDtos.CurrentUserResponse currentUser(AccountPrincipal principal) {
        String verificationStatus = "NOT_APPLICABLE";
        if (principal.roles().contains("ALUMNI")) {
            List<String> values = jdbc.query("SELECT verification_status FROM alumni_profiles WHERE user_id = ?",
                    (rs, rowNum) -> rs.getString(1), principal.id());
            verificationStatus = values.isEmpty() ? "PROFILE_REQUIRED" : values.get(0);
        } else if (principal.roles().contains("EMPLOYER")) {
            List<String> values = jdbc.query("SELECT review_status FROM employer_profiles WHERE user_id = ?",
                    (rs, rowNum) -> rs.getString(1), principal.id());
            verificationStatus = values.isEmpty() ? "PROFILE_REQUIRED" : values.get(0);
        } else if (principal.roles().contains("STUDENT")) {
            verificationStatus = "EMAIL_VERIFIED";
        }
        return new AuthDtos.CurrentUserResponse(principal.id(), principal.fullName(), principal.getUsername(),
                principal.roles(), principal.permissions(), "ACTIVE", verificationStatus);
    }

    private void validateAlumniRegistration(AuthDtos.RegisterRequest request) {
        if (request.studentCode() == null || request.studentCode().isBlank())
            throw validation("studentCode", "Mã sinh viên là bắt buộc.");
        if (request.facultyId() == null) throw validation("facultyId", "Khoa là bắt buộc.");
        if (request.cohortId() == null) throw validation("cohortId", "Khóa là bắt buộc.");
        if (request.graduationYear() == null || request.graduationYear() < 1900 || request.graduationYear() > 2200)
            throw validation("graduationYear", "Năm tốt nghiệp phải nằm trong khoảng 1900–2200.");
        if (!catalogIsActive("faculties", request.facultyId()))
            throw validation("facultyId", "Khoa không tồn tại hoặc đã ngừng hoạt động.");
        if (!catalogIsActive("cohorts", request.cohortId()))
            throw validation("cohortId", "Khóa không tồn tại hoặc đã ngừng hoạt động.");
        if (request.majorId() != null) {
            Integer matchingMajor = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM majors WHERE id = ? AND faculty_id = ? AND is_active = TRUE
                    """, Integer.class, request.majorId(), request.facultyId());
            if (matchingMajor == null || matchingMajor == 0)
                throw validation("majorId", "Ngành không thuộc khoa đã chọn hoặc đã ngừng hoạt động.");
        }
    }

    private void validateEmployerRegistration(AuthDtos.RegisterRequest request) {
        if (request.organizationName() == null || request.organizationName().isBlank())
            throw validation("organizationName", "Tên doanh nghiệp là bắt buộc.");
        if (request.businessRegistrationNumber() == null || request.businessRegistrationNumber().isBlank())
            throw validation("businessRegistrationNumber", "Mã số doanh nghiệp/MST là bắt buộc.");
        if (request.website() != null && !request.website().isBlank()) {
            try {
                java.net.URI website = java.net.URI.create(request.website().trim());
                if (!Set.of("http", "https").contains(website.getScheme() == null ? "" : website.getScheme().toLowerCase(Locale.ROOT))
                        || website.getHost() == null) throw new IllegalArgumentException("Invalid website");
            } catch (IllegalArgumentException exception) {
                throw validation("website", "Website phải là địa chỉ HTTP hoặc HTTPS hợp lệ.");
            }
        }
    }

    private void insertEmployerProfile(long accountId, AuthDtos.RegisterRequest request) {
        jdbc.update("""
                INSERT INTO employer_profiles (user_id, organization_name, business_registration_number, website)
                VALUES (?, ?, ?, ?)
                """, accountId, request.organizationName().trim(), request.businessRegistrationNumber().trim(),
                request.website() == null || request.website().isBlank() ? null : request.website().trim());
    }

    private boolean catalogIsActive(String table, long id) {
        // Table names come from fixed internal call sites, never from request data.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ? AND is_active = TRUE",
                Integer.class, id);
        return count != null && count > 0;
    }

    private void insertAlumniProfile(long accountId, AuthDtos.RegisterRequest request, String fullName) {
        jdbc.update("""
                INSERT INTO alumni_profiles
                    (user_id, student_code, full_name, faculty_id, major_id, cohort_id, graduation_year)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, accountId, request.studentCode().trim(), fullName, request.facultyId(), request.majorId(),
                request.cohortId(), request.graduationYear());
    }

    private long insertAccount(String email, String fullName) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO user_accounts (email, full_name, account_status)
                    VALUES (?, ?, 'PENDING_EMAIL')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, email);
            statement.setString(2, fullName);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return an account id");
        return key.longValue();
    }

    private ExistingAccount findAccount(String email) {
        List<ExistingAccount> accounts = jdbc.query("""
                SELECT ua.id, ua.account_status, r.code AS role_code
                FROM user_accounts ua LEFT JOIN user_roles ur ON ur.user_id = ua.id
                LEFT JOIN roles r ON r.id = ur.role_id
                WHERE ua.email = ? ORDER BY r.code LIMIT 1
                """, (rs, rowNum) -> new ExistingAccount(rs.getLong("id"),
                rs.getString("account_status"), rs.getString("role_code")), email);
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private Long findActiveAccountId(String email) {
        List<Long> ids = jdbc.query("SELECT id FROM user_accounts WHERE email = ? AND account_status = 'ACTIVE' LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), email);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void expireSessions(long accountId) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof AccountPrincipal account && account.id() == accountId) {
                for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                    session.expireNow();
                }
            }
        }
    }

    public static String recoveryMessage() { return GENERIC_RECOVERY_MESSAGE; }

    private record ExistingAccount(long id, String status, String role) {}
    private record ActivationTarget(String email, String status, int managedRoleCount) {}

    private static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + ": " + message);
    }

    private static ApiException invalidOtp() {
        return new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID_OR_EXPIRED",
                "Mã xác minh không hợp lệ hoặc đã hết hạn.");
    }
}
