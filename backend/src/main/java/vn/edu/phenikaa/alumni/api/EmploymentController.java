package vn.edu.phenikaa.alumni.api;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.phenikaa.alumni.profile.EmploymentService;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

@RestController
@RequestMapping("/api/v1/me/employment")
@PreAuthorize("hasRole('ALUMNI')")
public class EmploymentController {
    private final EmploymentService employment;

    public EmploymentController(EmploymentService employment) { this.employment = employment; }

    @GetMapping
    EmploymentDtos.EmploymentResponse get(@AuthenticationPrincipal AccountPrincipal principal) {
        return employment.get(principal.id());
    }

    @PatchMapping
    EmploymentDtos.EmploymentResponse patch(@AuthenticationPrincipal AccountPrincipal principal,
                                            @Valid @RequestBody EmploymentDtos.EmploymentUpdateRequest request) {
        return employment.update(principal.id(), request);
    }
}
