package vn.edu.phenikaa.alumni.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ReportDtos {
    private ReportDtos() {}

    public record Filters(Long facultyId, Long majorId, Long cohortId,
                          Integer graduationYear, String employmentStatus) {}

    public record FacultyOption(long id, String code, String name) {}
    public record MajorOption(long id, long facultyId, String code, String name) {}
    public record CohortOption(long id, String code, int admissionYear, int expectedGraduationYear) {}
    public record FilterOptions(List<FacultyOption> faculties, List<MajorOption> majors,
                                List<CohortOption> cohorts) {}

    public record AppliedFilters(Long facultyId, Long majorId, Long cohortId,
                                 Integer graduationYear, String employmentStatus) {}
    public record EmploymentStatusMetric(String code, long count, BigDecimal percentage,
                                         String denominator) {}
    public record Summary(Instant generatedAt, AppliedFilters appliedFilters,
                          long totalProfiles, long verifiedCount, long pendingCount,
                          long reportedCount, long unknownCount, BigDecimal responseCoverage,
                          List<EmploymentStatusMetric> employmentStatuses) {}
}
