package vn.edu.phenikaa.alumni.api;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.phenikaa.alumni.profile.AlumniProfileService;
import vn.edu.phenikaa.alumni.profile.AvatarStorageService;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/me/profile")
@PreAuthorize("hasAuthority('ROLE_ALUMNI')")
public class AlumniProfileController {
    public record ChangeRequestAccepted(long requestId, String status) {}

    private final AlumniProfileService profiles;
    private final AvatarStorageService avatars;

    public AlumniProfileController(AlumniProfileService profiles, AvatarStorageService avatars) {
        this.profiles = profiles;
        this.avatars = avatars;
    }

    @GetMapping
    AuthDtos.ProfileResponse get(@AuthenticationPrincipal AccountPrincipal principal) {
        return profiles.getProfile(principal.id());
    }

    @PatchMapping
    AuthDtos.ProfileResponse patch(@AuthenticationPrincipal AccountPrincipal principal,
                                   @RequestBody Map<String, Object> request) {
        return profiles.updateProfile(principal.id(), request);
    }

    @PostMapping("/change-requests")
    ResponseEntity<ChangeRequestAccepted> submitChange(@AuthenticationPrincipal AccountPrincipal principal,
                                                        @Valid @RequestBody AuthDtos.ProfileChangeRequest request) {
        long id = profiles.submitChangeRequest(principal.id(), request);
        return ResponseEntity.accepted().body(new ChangeRequestAccepted(id, "PENDING"));
    }

    @GetMapping("/change-requests")
    java.util.List<AuthDtos.ProfileChangeResponse> myChangeRequests(
            @AuthenticationPrincipal AccountPrincipal principal) {
        return profiles.getMyChangeRequests(principal.id());
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String, Object>> uploadAvatar(@AuthenticationPrincipal AccountPrincipal principal,
                                                       @RequestParam("file") MultipartFile file) {
        long id = avatars.replace(principal.id(), file);
        return ResponseEntity.ok(Map.of("fileId", id, "contentType", "image/png"));
    }

    @GetMapping(value = "/avatar", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> getAvatar(@AuthenticationPrincipal AccountPrincipal principal) {
        AvatarStorageService.AvatarDownload image = avatars.getCurrent(principal.id());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore()).body(image.content());
    }
}
