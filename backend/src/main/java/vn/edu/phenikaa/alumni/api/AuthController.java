package vn.edu.phenikaa.alumni.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;
import vn.edu.phenikaa.alumni.security.AuthService;
import vn.edu.phenikaa.alumni.security.OtpChallengeService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SessionRegistry sessionRegistry;

    public AuthController(AuthService authService, AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          SessionAuthenticationStrategy sessionAuthenticationStrategy,
                          SessionRegistry sessionRegistry) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.sessionRegistry = sessionRegistry;
    }

    @PostMapping("/register")
    ResponseEntity<AuthDtos.MessageResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                                       HttpServletRequest servletRequest) {
        AuthService.RegistrationResult result = authService.register(request, clientAddress(servletRequest));
        if (result.delivery() == OtpChallengeService.Delivery.FAILED && !result.duplicate()) {
            throw new EmailDeliveryException();
        }
        return ResponseEntity.accepted().body(new AuthDtos.MessageResponse(
                "Nếu thông tin hợp lệ, mã xác minh sẽ được gửi đến email đã nhập. Nếu chưa nhận được, hãy thử gửi lại sau."));
    }

    @PostMapping("/register/resend-otp")
    ResponseEntity<AuthDtos.MessageResponse> resendRegistration(@Valid @RequestBody AuthDtos.EmailRequest request,
                                                                HttpServletRequest servletRequest) {
        OtpChallengeService.Delivery delivery = authService.resendRegistration(request.email(), clientAddress(servletRequest));
        if (delivery == OtpChallengeService.Delivery.FAILED) throw new EmailDeliveryException();
        return ResponseEntity.accepted().body(new AuthDtos.MessageResponse(
                "Nếu thông tin phù hợp, mã xác minh sẽ được gửi đến email đã nhập."));
    }

    @PostMapping("/register/complete")
    ResponseEntity<Void> completeRegistration(@Valid @RequestBody AuthDtos.CompleteRegistrationRequest request) {
        authService.completeRegistration(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    AuthDtos.CurrentUserResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                       HttpServletRequest servletRequest,
                                       HttpServletResponse servletResponse) {
        String email = OtpChallengeService.normalizeEmail(request.email());
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));
        sessionAuthenticationStrategy.onAuthentication(authentication, servletRequest, servletResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        return authService.currentUser((AccountPrincipal) authentication.getPrincipal());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response,
                                @AuthenticationPrincipal AccountPrincipal principal) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            sessionRegistry.removeSessionInformation(session.getId());
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    AuthDtos.CurrentUserResponse currentUser(@AuthenticationPrincipal AccountPrincipal principal) {
        return authService.currentUser(principal);
    }

    @PostMapping("/password-recovery-requests")
    ResponseEntity<AuthDtos.MessageResponse> requestRecovery(@Valid @RequestBody AuthDtos.EmailRequest request,
                                                              HttpServletRequest servletRequest) {
        authService.requestRecovery(request.email(), clientAddress(servletRequest));
        return ResponseEntity.accepted().body(new AuthDtos.MessageResponse(AuthService.recoveryMessage()));
    }

    @PostMapping("/password-recovery/resend-otp")
    ResponseEntity<AuthDtos.MessageResponse> resendRecovery(@Valid @RequestBody AuthDtos.EmailRequest request,
                                                             HttpServletRequest servletRequest) {
        authService.resendRecovery(request.email(), clientAddress(servletRequest));
        return ResponseEntity.accepted().body(new AuthDtos.MessageResponse(AuthService.recoveryMessage()));
    }

    @PostMapping("/password-recovery/complete")
    ResponseEntity<Void> completeRecovery(@Valid @RequestBody AuthDtos.CompleteRecoveryRequest request) {
        authService.completeRecovery(request);
        return ResponseEntity.noContent().build();
    }

    private static String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
