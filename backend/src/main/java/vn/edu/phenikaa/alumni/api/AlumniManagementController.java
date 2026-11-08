package vn.edu.phenikaa.alumni.api;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.phenikaa.alumni.profile.AlumniManagementService;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/staff/alumni")
@PreAuthorize("hasAnyRole('FACULTY_STAFF','ADMIN')")
public class AlumniManagementController {
    private final AlumniManagementService alumni;

    public AlumniManagementController(AlumniManagementService alumni) { this.alumni = alumni; }

    @GetMapping("/catalog-options")
    AlumniManagementDtos.CatalogOptions catalogOptions(@AuthenticationPrincipal AccountPrincipal principal) {
        return alumni.catalogOptions(principal);
    }

    @GetMapping
    AlumniManagementDtos.ProfilePage list(@AuthenticationPrincipal AccountPrincipal principal,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) Long facultyId,
                                           @RequestParam(required = false) Long majorId,
                                           @RequestParam(required = false) Long cohortId,
                                           @RequestParam(required = false) Integer graduationYear,
                                           @RequestParam(required = false) String employmentStatus,
                                           @RequestParam(defaultValue = "false") boolean includeArchived,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return alumni.list(principal, q, facultyId, majorId, cohortId, graduationYear,
                employmentStatus, includeArchived, page, size);
    }

    @GetMapping("/{profileId}")
    AlumniManagementDtos.ProfileDetail get(@PathVariable long profileId,
                                            @AuthenticationPrincipal AccountPrincipal principal) {
        return alumni.get(profileId, principal);
    }

    @PostMapping
    AlumniManagementDtos.ProfileDetail create(@Valid @RequestBody AlumniManagementDtos.CreateRequest request,
                                               @AuthenticationPrincipal AccountPrincipal principal) {
        return alumni.create(request, principal);
    }

    @PatchMapping("/{profileId}")
    AlumniManagementDtos.ProfileDetail update(@PathVariable long profileId,
                                               @RequestBody Map<String, Object> request,
                                               @AuthenticationPrincipal AccountPrincipal principal) {
        return alumni.update(profileId, request, principal);
    }

    @PostMapping("/{profileId}/archive")
    AlumniManagementDtos.ProfileDetail archive(@PathVariable long profileId,
                                                @AuthenticationPrincipal AccountPrincipal principal) {
        return alumni.archive(profileId, principal);
    }

    @PostMapping("/{profileId}/restore")
    AlumniManagementDtos.ProfileDetail restore(@PathVariable long profileId,
                                                @AuthenticationPrincipal AccountPrincipal principal) {
        return alumni.restore(profileId, principal);
    }
}
