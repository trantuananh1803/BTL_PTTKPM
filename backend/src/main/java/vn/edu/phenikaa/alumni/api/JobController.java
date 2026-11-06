package vn.edu.phenikaa.alumni.api;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.phenikaa.alumni.jobs.JobService;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class JobController {
    private final JobService jobs;

    public JobController(JobService jobs) { this.jobs = jobs; }

    @GetMapping("/jobs")
    @PreAuthorize("hasAnyRole('ALUMNI','STUDENT')")
    JobDtos.JobPage search(@AuthenticationPrincipal AccountPrincipal principal,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String location,
                           @RequestParam(required = false) String employmentType,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return jobs.search(principal, keyword, location, employmentType, page, size);
    }

    @PostMapping("/jobs/applications")
    @PreAuthorize("hasAnyRole('ALUMNI','STUDENT')")
    JobDtos.ApplicationView apply(@AuthenticationPrincipal AccountPrincipal principal,
                                  @RequestParam long jobId,
                                  @RequestPart("cv") MultipartFile cv) {
        return jobs.apply(principal, jobId, cv);
    }

    @GetMapping("/me/job-applications")
    @PreAuthorize("hasAnyRole('ALUMNI','STUDENT')")
    List<JobDtos.ApplicationView> myApplications(@AuthenticationPrincipal AccountPrincipal principal) {
        return jobs.myApplications(principal);
    }

    @GetMapping("/me/job-applications/{applicationId}/cv")
    @PreAuthorize("hasAnyRole('ALUMNI','STUDENT')")
    ResponseEntity<byte[]> myCv(@AuthenticationPrincipal AccountPrincipal principal,
                                @PathVariable long applicationId) {
        return cvResponse(jobs.getApplicantCv(principal, applicationId));
    }

    @GetMapping("/employer/jobs")
    @PreAuthorize("hasRole('EMPLOYER')")
    List<JobDtos.JobView> employerJobs(@AuthenticationPrincipal AccountPrincipal principal) {
        return jobs.employerJobs(principal);
    }

    @PostMapping("/employer/jobs")
    @PreAuthorize("hasRole('EMPLOYER')")
    JobDtos.JobView createJob(@AuthenticationPrincipal AccountPrincipal principal,
                              @Valid @RequestBody JobDtos.JobPostRequest request) {
        return jobs.createJob(principal, request);
    }

    @PatchMapping("/employer/jobs/{jobId}")
    @PreAuthorize("hasRole('EMPLOYER')")
    JobDtos.JobView updateJob(@AuthenticationPrincipal AccountPrincipal principal,
                              @PathVariable long jobId,
                              @Valid @RequestBody JobDtos.JobPostRequest request) {
        return jobs.updateJob(principal, jobId, request);
    }

    @PostMapping("/employer/jobs/{jobId}/close")
    @PreAuthorize("hasRole('EMPLOYER')")
    JobDtos.JobView closeJob(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long jobId) {
        return jobs.closeJob(principal, jobId);
    }

    @GetMapping("/employer/applications")
    @PreAuthorize("hasRole('EMPLOYER')")
    List<JobDtos.EmployerApplicationView> employerApplications(@AuthenticationPrincipal AccountPrincipal principal) {
        return jobs.employerApplications(principal);
    }

    @GetMapping("/employer/applications/{applicationId}/cv")
    @PreAuthorize("hasRole('EMPLOYER')")
    ResponseEntity<byte[]> employerCv(@AuthenticationPrincipal AccountPrincipal principal,
                                      @PathVariable long applicationId) {
        return cvResponse(jobs.getEmployerCv(principal, applicationId));
    }

    private ResponseEntity<byte[]> cvResponse(JobService.CvDownload cv) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("cv.pdf", StandardCharsets.UTF_8).build().toString())
                .body(cv.content());
    }
}
