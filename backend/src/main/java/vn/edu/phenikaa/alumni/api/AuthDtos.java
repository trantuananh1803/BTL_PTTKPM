package vn.edu.phenikaa.alumni.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(max = 180) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank String role,
            @Size(max = 32) String studentCode,
            Long facultyId,
            Long majorId,
            Long cohortId,
            Integer graduationYear,
            @Size(max = 180) String organizationName,
            @Size(max = 40) String businessRegistrationNumber,
            @Size(max = 255) String website) {}

    public record OtpRequest(@NotBlank @Email @Size(max = 254) String email,
                             @NotBlank @Size(min = 8, max = 8) String otp) {}

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record CompleteRegistrationRequest(@NotBlank @Email @Size(max = 254) String email,
                                               @NotBlank @Size(min = 8, max = 8) String otp,
                                               @NotBlank String password) {}

    public record CompleteRecoveryRequest(@NotBlank @Email @Size(max = 254) String email,
                                           @NotBlank @Size(min = 8, max = 8) String otp,
                                           @NotBlank String newPassword) {}

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank String password) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank String newPassword) {}

    public record MessageResponse(String message) {}

    public record CurrentUserResponse(long id, String fullName, String email,
                                      java.util.List<String> roles,
                                      java.util.List<String> permissions,
                                      String accountStatus, String verificationStatus) {}

    public record ProfilePatchRequest(@Size(max = 254) @Email String personalEmail,
                                      @Size(max = 32) String phone,
                                      String directoryVisibility) {}

    public record ProfileChangeRequest(@NotNull java.util.Map<String, Object> proposedValues) {}

    public record ProfileResponse(long id, String studentCode, String fullName,
                                  long facultyId, String facultyName, Long majorId, String majorName,
                                  long cohortId, String cohortCode, int graduationYear,
                                  String personalEmail, String phone, String employmentStatus,
                                  String employerName, String jobTitle, String directoryVisibility,
                                  String verificationStatus, java.time.Instant verifiedAt,
                                  java.time.Instant updatedAt, Long avatarFileId) {}

    public record ProfileChangeResponse(long id, java.util.Map<String, Object> proposedValues,
                                        String status, String reviewNote,
                                        java.time.Instant submittedAt, java.time.Instant reviewedAt) {}
}
