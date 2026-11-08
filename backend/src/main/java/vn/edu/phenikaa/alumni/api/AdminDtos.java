package vn.edu.phenikaa.alumni.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public final class AdminDtos {
    private AdminDtos() {}

    public record AccountCreateRequest(@NotBlank @Email @Size(max = 254) String email,
                                       @NotBlank @Size(max = 180) String fullName,
                                       @NotEmpty List<String> roles,
                                       List<Long> facultyIds,
                                       List<String> permissions) {}

    public record AccountUpdateRequest(@NotEmpty List<String> roles,
                                       List<Long> facultyIds,
                                       List<String> permissions,
                                       String accountStatus) {}

    public record AccountSummary(long id, String email, String fullName, String accountStatus,
                                 boolean emailVerified, List<String> roles, List<String> permissions,
                                 List<Long> facultyIds) {}

    public record AlumniPromotionCandidate(long profileId, String studentCode, String fullName,
                                           String facultyName, String cohortCode) {}

    public record AlumniPromotionRequest(@Positive long alumniProfileId, boolean confirmIdentityMatch) {}

    public record CatalogChangeRequest(String type, String code, String name, Long facultyId,
                                       Integer admissionYear, Integer expectedGraduationYear,
                                       Boolean active) {}

    public record CatalogItem(String type, long id, String code, String name, boolean active,
                              Long facultyId, Integer admissionYear, Integer expectedGraduationYear) {}

    public record ReviewDecisionRequest(@NotBlank String decision, @Size(max = 1000) String reviewNote) {}

    public record ReviewItem(String type, long id, String status, String displayName,
                             String email, String studentCode, String facultyName, String majorName,
                             String cohortCode, Integer graduationYear, String organizationName,
                             String businessRegistrationNumber, String website, String jobTitle,
                             String jobDescription, java.time.Instant submittedAt, String reviewNote,
                             Map<String, Object> proposedValues) {}
}
