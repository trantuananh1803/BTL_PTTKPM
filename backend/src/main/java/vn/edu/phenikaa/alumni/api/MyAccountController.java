package vn.edu.phenikaa.alumni.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;
import vn.edu.phenikaa.alumni.security.AuthService;

@RestController
@RequestMapping("/api/v1/me")
public class MyAccountController {
    private final AuthService authService;

    public MyAccountController(AuthService authService) { this.authService = authService; }

    @PostMapping("/password")
    ResponseEntity<Void> changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest request,
                                        @AuthenticationPrincipal AccountPrincipal principal) {
        authService.changePassword(principal.id(), request);
        return ResponseEntity.noContent().build();
    }
}
