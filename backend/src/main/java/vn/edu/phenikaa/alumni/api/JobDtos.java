package vn.edu.phenikaa.alumni.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class JobDtos {
    private JobDtos() {}

    public record JobPostRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 180) String department,
            @Size(max = 180) String location,
            @Pattern(regexp = "FULL_TIME|PART_TIME|INTERNSHIP") String employmentType,
            @NotBlank @Size(max = 12000) String description,
            @Size(max = 12000) String requirements,
            LocalDate applicationDeadline) {}

    public record JobView(long id, String title, String department, String location,
                          String employmentType, String description, String requirements,
                          LocalDate applicationDeadline, String status, String organizationName,
                          Instant createdAt, Instant updatedAt, long applicationCount,
                          boolean applied, Long applicationId) {}

    public record JobPage(List<JobView> items, int page, int size, long totalElements) {}

    public record ApplicationView(long id, long jobId, String jobTitle, String organizationName,
                                  String status, Instant submittedAt, boolean cvAvailable) {}

    public record EmployerApplicationView(long id, long jobId, String jobTitle, String applicantName,
                                          String status, Instant submittedAt, boolean cvAvailable) {}
}
