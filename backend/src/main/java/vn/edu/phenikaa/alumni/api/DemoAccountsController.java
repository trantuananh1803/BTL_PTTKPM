package vn.edu.phenikaa.alumni.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exposes synthetic login hints only when the local demo-account seed is enabled. */
@RestController
@Profile("dev")
@ConditionalOnProperty(prefix = "app.demo-accounts", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/auth/demo-accounts")
public class DemoAccountsController {
    public record Hint(String role, String roleName, String email) {}
    public record Hints(List<Hint> accounts, String sharedPassword) {}

    private final String sharedPassword;

    public DemoAccountsController(@Value("${app.demo-accounts.password}") String sharedPassword) {
        this.sharedPassword = sharedPassword;
    }

    @GetMapping
    public Hints list() {
        return new Hints(List.of(
                new Hint("ALUMNI", "Cựu sinh viên", "alumni.demo@example.com"),
                new Hint("STUDENT", "Sinh viên", "student.demo@example.com"),
                new Hint("EMPLOYER", "Nhà tuyển dụng", "employer.demo@example.com"),
                new Hint("FACULTY_STAFF", "Cán bộ quản lý", "staff.demo@example.com"),
                new Hint("ADMIN", "Quản trị viên", "admin.demo@example.com")
        ), sharedPassword);
    }
}
