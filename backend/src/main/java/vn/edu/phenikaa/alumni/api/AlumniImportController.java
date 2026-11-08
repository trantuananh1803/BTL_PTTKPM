package vn.edu.phenikaa.alumni.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.phenikaa.alumni.imports.AlumniImportService;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

@RestController
@RequestMapping("/api/v1/staff/alumni-imports")
@PreAuthorize("hasAnyRole('FACULTY_STAFF','ADMIN')")
public class AlumniImportController {
    private final AlumniImportService importService;
    public AlumniImportController(AlumniImportService importService) { this.importService = importService; }

    @GetMapping("/template")
    ResponseEntity<byte[]> template(@AuthenticationPrincipal AccountPrincipal principal) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=alumni-import-template.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(importService.template(principal));
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    AlumniImportService.ImportBatchView preview(@RequestPart("file") MultipartFile file,
                                                 @AuthenticationPrincipal AccountPrincipal principal) {
        return importService.preview(file, principal);
    }

    @GetMapping("/{id}")
    AlumniImportService.ImportBatchView get(@PathVariable long id,
                                             @AuthenticationPrincipal AccountPrincipal principal) {
        return importService.get(id, principal);
    }

    @PostMapping("/{id}/commit")
    AlumniImportService.ImportBatchView commit(@PathVariable long id,
                                                @AuthenticationPrincipal AccountPrincipal principal) {
        return importService.commit(id, principal);
    }
}
