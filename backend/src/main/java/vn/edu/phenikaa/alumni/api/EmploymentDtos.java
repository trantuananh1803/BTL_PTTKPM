package vn.edu.phenikaa.alumni.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class EmploymentDtos {
    private EmploymentDtos() {}

    public record EmploymentResponse(String employmentStatus, String employerName, String jobTitle,
                                    String industry, Instant updatedAt) {}

    public record EmploymentUpdateRequest(@NotBlank String employmentStatus,
                                         @Size(max = 180) String employerName,
                                         @Size(max = 180) String jobTitle,
                                         @Size(max = 180) String industry) {}
}
