package vn.edu.phenikaa.alumni.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/** Optional one-time bootstrap for an empty, private development database. */
@Component
@Order(0)
public class InitialAdminBootstrap implements ApplicationRunner {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final boolean enabled;
    private final String email;
    private final String password;

    public InitialAdminBootstrap(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                                 @Value("${app.bootstrap-admin.enabled:false}") boolean enabled,
                                 @Value("${app.bootstrap-admin.email:}") String email,
                                 @Value("${app.bootstrap-admin.password:}") String password) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.enabled = enabled;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (!StringUtils.hasText(email) || !EMAIL.matcher(email.trim()).matches()) {
            throw new IllegalStateException("Bootstrap ADMIN requires a valid BOOTSTRAP_ADMIN_EMAIL.");
        }
        passwordPolicy.validate(password);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class);
        if (count == null || count != 0) return;

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO user_accounts (email, password_hash, full_name, account_status, email_verified_at)
                VALUES (?, ?, 'Quản trị viên', 'ACTIVE', CURRENT_TIMESTAMP(6))
                """, normalizedEmail, passwordEncoder.encode(password));
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT id, (SELECT id FROM roles WHERE code = 'ADMIN')
                FROM user_accounts WHERE email = ?
                """, normalizedEmail);
        jdbc.update("""
                INSERT INTO user_permissions (user_id, permission_id)
                SELECT ua.id, p.id FROM user_accounts ua
                CROSS JOIN permissions p
                WHERE ua.email = ? AND p.code IN ('EMPLOYER_REVIEW', 'JOB_REVIEW', 'ALUMNI_IMPORT')
                """, normalizedEmail);
    }
}
