package vn.edu.phenikaa.alumni.api;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.phenikaa.alumni.reports.ReportExportService;
import vn.edu.phenikaa.alumni.reports.ReportService;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasAnyRole('FACULTY_STAFF','ADMIN')")
public class ReportController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ReportService reports;
    private final ReportExportService exports;

    public ReportController(ReportService reports, ReportExportService exports) {
        this.reports = reports;
        this.exports = exports;
    }

    @GetMapping("/filters")
    ReportDtos.FilterOptions filters(@AuthenticationPrincipal AccountPrincipal principal) {
        return reports.filterOptions(principal);
    }

    @GetMapping({"/alumni", "/employment"})
    ReportDtos.Summary summary(@RequestParam(required = false) Long facultyId,
                               @RequestParam(required = false) Long majorId,
                               @RequestParam(required = false) Long cohortId,
                               @RequestParam(required = false) Integer graduationYear,
                               @RequestParam(required = false) String employmentStatus,
                               @AuthenticationPrincipal AccountPrincipal principal) {
        return reports.summarize(new ReportDtos.Filters(facultyId, majorId, cohortId, graduationYear,
                employmentStatus), principal);
    }

    @GetMapping("/alumni/export")
    ResponseEntity<byte[]> exportAlumni(@RequestParam(required = false) Long facultyId,
                                        @RequestParam(required = false) Long majorId,
                                        @RequestParam(required = false) Long cohortId,
                                        @RequestParam(required = false) Integer graduationYear,
                                        @RequestParam(required = false) String employmentStatus,
                                        @RequestParam(defaultValue = "xlsx") String format,
                                        @AuthenticationPrincipal AccountPrincipal principal) {
        return export("alumni", new ReportDtos.Filters(facultyId, majorId, cohortId, graduationYear,
                employmentStatus), format, principal);
    }

    @GetMapping("/employment/export")
    ResponseEntity<byte[]> exportEmployment(@RequestParam(required = false) Long facultyId,
                                            @RequestParam(required = false) Long majorId,
                                            @RequestParam(required = false) Long cohortId,
                                            @RequestParam(required = false) Integer graduationYear,
                                            @RequestParam(required = false) String employmentStatus,
                                            @RequestParam(defaultValue = "xlsx") String format,
                                            @AuthenticationPrincipal AccountPrincipal principal) {
        return export("employment", new ReportDtos.Filters(facultyId, majorId, cohortId, graduationYear,
                employmentStatus), format, principal);
    }

    private ResponseEntity<byte[]> export(String kind, ReportDtos.Filters filters, String requestedFormat,
                                          AccountPrincipal principal) {
        String format = requestedFormat == null ? "" : requestedFormat.trim().toLowerCase(java.util.Locale.ROOT);
        ReportDtos.Summary summary = reports.summarize(filters, principal);
        String base = "bao-cao-" + kind + "-" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"));
        byte[] bytes;
        MediaType contentType;
        String extension;
        if ("xlsx".equals(format)) {
            bytes = exports.xlsx(kind, summary);
            contentType = XLSX;
            extension = "xlsx";
        } else if ("pdf".equals(format)) {
            bytes = exports.pdf(kind, summary);
            contentType = MediaType.APPLICATION_PDF;
            extension = "pdf";
        } else {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REPORT_FORMAT",
                    "Định dạng báo cáo chỉ hỗ trợ pdf hoặc xlsx.");
        }
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(bytes.length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + base + "." + extension + "\"; filename*=UTF-8''" +
                                java.net.URLEncoder.encode(base + "." + extension, StandardCharsets.UTF_8).replace("+", "%20"))
                .body(bytes);
    }
}
