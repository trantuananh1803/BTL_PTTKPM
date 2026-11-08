package vn.edu.phenikaa.alumni.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

public final class AccountPrincipal implements UserDetails {
    private final long id;
    private final String email;
    private final String passwordHash;
    private final String fullName;
    private final List<String> roles;
    private final List<String> permissions;

    public AccountPrincipal(long id, String email, String passwordHash, String fullName,
                            List<String> roles, List<String> permissions) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.roles = List.copyOf(roles);
        this.permissions = List.copyOf(permissions);
    }

    public long id() { return id; }
    public String fullName() { return fullName; }
    public List<String> roles() { return roles; }
    public List<String> permissions() { return permissions; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return Stream.concat(roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)),
                permissions.stream().map(SimpleGrantedAuthority::new)).toList();
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
    @Override public boolean equals(Object other) {
        return other instanceof AccountPrincipal principal && principal.id == id;
    }
    @Override public int hashCode() { return Long.hashCode(id); }
}
