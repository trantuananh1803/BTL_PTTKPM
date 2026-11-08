package vn.edu.phenikaa.alumni.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/public/catalogs")
public class PublicCatalogController {
    public record FacultyOption(long id, String code, String name) {}
    public record MajorOption(long id, long facultyId, String code, String name) {}
    public record CohortOption(long id, String code, int admissionYear, int expectedGraduationYear) {}
    public record CatalogOptions(List<FacultyOption> faculties, List<MajorOption> majors,
                                 List<CohortOption> cohorts) {}

    private final JdbcTemplate jdbc;
    public PublicCatalogController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    CatalogOptions getOptions() {
        List<FacultyOption> faculties = jdbc.query("""
                SELECT id, code, name FROM faculties WHERE is_active = TRUE ORDER BY name
                """, (rs, rowNum) -> new FacultyOption(rs.getLong("id"), rs.getString("code"), rs.getString("name")));
        List<MajorOption> majors = jdbc.query("""
                SELECT id, faculty_id, code, name FROM majors WHERE is_active = TRUE ORDER BY name
                """, (rs, rowNum) -> new MajorOption(rs.getLong("id"), rs.getLong("faculty_id"),
                rs.getString("code"), rs.getString("name")));
        List<CohortOption> cohorts = jdbc.query("""
                SELECT id, code, admission_year, expected_graduation_year
                FROM cohorts WHERE is_active = TRUE ORDER BY admission_year DESC
                """, (rs, rowNum) -> new CohortOption(rs.getLong("id"), rs.getString("code"),
                rs.getInt("admission_year"), rs.getInt("expected_graduation_year")));
        return new CatalogOptions(faculties, majors, cohorts);
    }
}
