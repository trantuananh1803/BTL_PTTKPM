package vn.edu.phenikaa.alumni.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class AlumniManagementDtos {
    private AlumniManagementDtos() {}

    public record CreateRequest(
            @NotBlank @Size(max = 32) String studentCode,
            @NotBlank @Size(max = 180) String fullName,
            @NotNull Long facultyId,
            Long majorId,
            @NotNull Long cohortId,
            @NotNull Integer graduationYear,
            @Email @Size(max = 254) String personalEmail,
            @Size(max = 32) String phone,
            String employmentStatus,
            @Size(max = 180) String employerName,
            @Size(max = 180) String jobTitle,
            @Size(max = 180) String industry,
            String directoryVisibility) {}

    public record ProfileSummary(long id, String studentCode, String fullName,
                                 long facultyId, String facultyName,
                                 Long majorId, String majorName,
                                 long cohortId, String cohortCode, Integer graduationYear,
                                 String employmentStatus, String verificationStatus,
                                 Instant archivedAt) {}

    public record ProfileDetail(long id, String studentCode, String fullName,
                                long facultyId, String facultyName,
                                Long majorId, String majorName,
                                long cohortId, String cohortCode, Integer graduationYear,
                                String personalEmail, String phone, String employmentStatus,
                                String employerName, String jobTitle, String industry,
                                String directoryVisibility, String verificationStatus,
                                Instant archivedAt, Instant updatedAt) {}

    public record ProfilePage(List<ProfileSummary> items, int page, int size, long totalElements) {}
    public record CatalogOption(long id, String code, String name, Long facultyId) {}
    public record CohortOption(long id, String code, int admissionYear, int expectedGraduationYear) {}
    public record CatalogOptions(List<CatalogOption> faculties, List<CatalogOption> majors,
                                 List<CohortOption> cohorts) {}
}
