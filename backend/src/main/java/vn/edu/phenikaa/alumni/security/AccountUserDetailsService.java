package vn.edu.phenikaa.alumni.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class AccountUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public AccountUserDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = username.trim().toLowerCase(Locale.ROOT);
        List<AccountPrincipal> accounts = jdbc.query("""
                SELECT id, email, password_hash, full_name
                FROM user_accounts WHERE email = ? AND account_status = 'ACTIVE'
                """, (rs, rowNum) -> new AccountPrincipal(rs.getLong("id"), rs.getString("email"),
                rs.getString("password_hash"), rs.getString("full_name"), List.of(), List.of()), email);
        if (accounts.isEmpty() || accounts.get(0).getPassword() == null) {
            throw new UsernameNotFoundException("Account not found");
        }
        AccountPrincipal account = accounts.get(0);
        List<String> roles = jdbc.queryForList("""
                SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id = r.id
                WHERE ur.user_id = ? ORDER BY r.code
                """, String.class, account.id());
        List<String> permissions = jdbc.queryForList("""
                SELECT p.code FROM permissions p JOIN user_permissions up ON up.permission_id = p.id
                WHERE up.user_id = ? ORDER BY p.code
                """, String.class, account.id());
        return new AccountPrincipal(account.id(), account.getUsername(), account.getPassword(),
                account.fullName(), roles, permissions);
    }
}
