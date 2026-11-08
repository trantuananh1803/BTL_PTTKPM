package vn.edu.phenikaa.alumni.api;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;
import vn.edu.phenikaa.alumni.security.AdminService;
import vn.edu.phenikaa.alumni.security.AuthService;
import vn.edu.phenikaa.alumni.security.OtpChallengeService;
import vn.edu.phenikaa.alumni.catalogs.CatalogService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final AdminService adminService;
    private final CatalogService catalogService;
    private final AuthService authService;

    public AdminController(AdminService adminService, CatalogService catalogService, AuthService authService) {
        this.adminService = adminService;
        this.catalogService = catalogService;
        this.authService = authService;
    }

    @GetMapping("/accounts")
    List<AdminDtos.AccountSummary> accounts(@RequestParam(required = false) String q) {
        return adminService.listAccounts(q);
    }

    @GetMapping("/accounts/alumni-promotion-candidates")
    List<AdminDtos.AlumniPromotionCandidate> alumniPromotionCandidates() {
        return adminService.listAlumniPromotionCandidates();
    }

    @PostMapping("/accounts/{id}/alumni-promotion")
    AdminDtos.AccountSummary promoteStudentToAlumni(@PathVariable long id,
                                                     @Valid @RequestBody AdminDtos.AlumniPromotionRequest request,
                                                     @AuthenticationPrincipal AccountPrincipal principal) {
        return adminService.promoteStudentToAlumni(id, request.alumniProfileId(), request.confirmIdentityMatch(), principal.id());
    }

    @PostMapping("/accounts")
    AdminDtos.AccountSummary createAccount(@Valid @RequestBody AdminDtos.AccountCreateRequest request,
                                            @AuthenticationPrincipal AccountPrincipal principal) {
        return adminService.createAccount(request, principal.id());
    }

    @PatchMapping("/accounts/{id}")
    AdminDtos.AccountSummary updateAccount(@PathVariable long id,
                                            @Valid @RequestBody AdminDtos.AccountUpdateRequest request,
                                            @AuthenticationPrincipal AccountPrincipal principal) {
        return adminService.updateAccount(id, request, principal.id());
    }

    @PostMapping("/accounts/{id}/activation-otp")
    AuthDtos.MessageResponse sendActivationOtp(@PathVariable long id,
                                                @AuthenticationPrincipal AccountPrincipal principal,
                                                jakarta.servlet.http.HttpServletRequest request) {
        OtpChallengeService.Delivery delivery = authService.issueStaffActivationOtp(id, request.getRemoteAddr());
        if (delivery == OtpChallengeService.Delivery.FAILED) throw new EmailDeliveryException();
        return new AuthDtos.MessageResponse("Yêu cầu gửi mã kích hoạt đã được tiếp nhận theo cấu hình Email hiện tại.");
    }

    @GetMapping("/catalogs")
    List<AdminDtos.CatalogItem> catalogs(@RequestParam String type) {
        return catalogService.list(type);
    }

    @PostMapping("/catalogs")
    AdminDtos.CatalogItem createCatalog(@Valid @RequestBody AdminDtos.CatalogChangeRequest request,
                                         @AuthenticationPrincipal AccountPrincipal principal) {
        return catalogService.create(request, principal.id());
    }

    @PatchMapping("/catalogs/{type}/{id}")
    AdminDtos.CatalogItem updateCatalog(@PathVariable String type, @PathVariable long id,
                                         @Valid @RequestBody AdminDtos.CatalogChangeRequest request,
                                         @AuthenticationPrincipal AccountPrincipal principal) {
        return catalogService.update(type, id, request, principal.id());
    }
}
