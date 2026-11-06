package vn.edu.phenikaa.alumni.jobs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.api.JobDtos;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class JobService {
    private static final long MAX_CV_BYTES = 10L * 1024 * 1024;
    private static final List<String> EMPLOYMENT_TYPES = List.of("FULL_TIME", "PART_TIME", "INTERNSHIP");

    public record CvDownload(byte[] content) {}
    private record StoredFile(long id, String storageKey) {}

    private final JdbcTemplate jdbc;
    private final Path privateDirectory;

    public JobService(JdbcTemplate jdbc,
                      @Value("${app.storage.private-directory:./var/private-files}") String privateDirectory) {
        this.jdbc = jdbc;
        this.privateDirectory = Path.of(privateDirectory).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public JobDtos.JobPage search(AccountPrincipal actor, String keyword, String location,
                                  String employmentType, int page, int size) {
        requireCandidate(actor);
        if (page < 0 || size < 1 || size > 100) throw invalid("Trang phải từ 0 và kích thước từ 1 đến 100.");
        String normalizedKeyword = optionalFilter(keyword, "Từ khóa", 120);
        String normalizedLocation = optionalFilter(location, "Địa điểm", 120);
        String type = normalizeEmploymentType(employmentType);
        String keywordPattern = normalizedKeyword == null ? null : "%" + normalizedKeyword + "%";
        String locationPattern = normalizedLocation == null ? null : "%" + normalizedLocation + "%";

        String conditions = " WHERE jp.status = 'PUBLISHED' AND (jp.application_deadline IS NULL OR jp.application_deadline >= CURRENT_DATE())"
                + " AND (? IS NULL OR jp.employment_type = ?)"
                + " AND (? IS NULL OR jp.location LIKE ?)"
                + " AND (? IS NULL OR jp.title LIKE ? OR jp.department LIKE ? OR jp.description LIKE ? OR ep.organization_name LIKE ?)";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM job_postings jp"
                        + " JOIN employer_profiles ep ON ep.user_id = jp.employer_user_id" + conditions,
                Long.class, type, type, locationPattern, locationPattern,
                keywordPattern, keywordPattern, keywordPattern, keywordPattern, keywordPattern);
        List<JobDtos.JobView> items = jdbc.query("""
                SELECT jp.id, jp.title, jp.department, jp.location, jp.employment_type, jp.description,
                       jp.requirements, jp.application_deadline, jp.status, ep.organization_name,
                       jp.created_at, jp.updated_at,
                       0 AS application_count,
                       EXISTS(SELECT 1 FROM job_applications own
                              WHERE own.job_posting_id = jp.id AND own.applicant_account_id = ?) AS applied,
                       (SELECT own.id FROM job_applications own
                        WHERE own.job_posting_id = jp.id AND own.applicant_account_id = ? LIMIT 1) AS application_id
                FROM job_postings jp
                JOIN employer_profiles ep ON ep.user_id = jp.employer_user_id
                """ + conditions + " ORDER BY jp.created_at DESC, jp.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> jobView(rs), actor.id(), actor.id(), type, type, locationPattern, locationPattern,
                keywordPattern, keywordPattern, keywordPattern, keywordPattern, keywordPattern,
                size, Math.multiplyExact((long) page, size));
        return new JobDtos.JobPage(items, page, size, total == null ? 0 : total);
    }

    @Transactional
    public JobDtos.ApplicationView apply(AccountPrincipal actor, long jobId, MultipartFile upload) {
        requireCandidate(actor);
        if (jobId < 1) throw invalid("Mã tin tuyển dụng không hợp lệ.");
        List<Long> jobs = jdbc.query("""
                SELECT id FROM job_postings
                WHERE id = ? AND status = 'PUBLISHED'
                  AND (application_deadline IS NULL OR application_deadline >= CURRENT_DATE())
                FOR UPDATE
                """, (rs, rowNum) -> rs.getLong(1), jobId);
        if (jobs.isEmpty()) throw notFound("Không tìm thấy tin đang nhận hồ sơ.");
        byte[] pdf = validateCv(upload);
        StoredFile file = storeCv(actor.id(), pdf);
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO job_applications (job_posting_id, applicant_account_id, cv_file_id)
                        VALUES (?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, jobId);
                statement.setLong(2, actor.id());
                statement.setLong(3, file.id());
                return statement;
            }, keys);
            Number id = keys.getKey();
            if (id == null) throw new IllegalStateException("Database did not return an application id");
            jdbc.update("""
                    INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields)
                    VALUES (?, 'JOB_APPLICATION_SUBMITTED', 'JOB_APPLICATION', ?, JSON_ARRAY('jobPostingId','cv'))
                    """, actor.id(), id.toString());
            return applicationById(id.longValue(), actor.id());
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "APPLICATION_ALREADY_EXISTS",
                    "Tài khoản này đã ứng tuyển vào tin tuyển dụng.");
        }
    }

    @Transactional(readOnly = true)
    public List<JobDtos.ApplicationView> myApplications(AccountPrincipal actor) {
        requireCandidate(actor);
        return jdbc.query("""
                SELECT ja.id, jp.id AS job_id, jp.title, ep.organization_name, ja.status,
                       ja.submitted_at, ja.cv_file_id
                FROM job_applications ja
                JOIN job_postings jp ON jp.id = ja.job_posting_id
                JOIN employer_profiles ep ON ep.user_id = jp.employer_user_id
                WHERE ja.applicant_account_id = ?
                ORDER BY ja.submitted_at DESC, ja.id DESC
                """, (rs, rowNum) -> new JobDtos.ApplicationView(
                rs.getLong("id"), rs.getLong("job_id"), rs.getString("title"),
                rs.getString("organization_name"), rs.getString("status"), instant(rs.getTimestamp("submitted_at")),
                rs.getObject("cv_file_id") != null), actor.id());
    }

    @Transactional(readOnly = true)
    public List<JobDtos.JobView> employerJobs(AccountPrincipal actor) {
        requireApprovedEmployer(actor);
        return jdbc.query("""
                SELECT jp.id, jp.title, jp.department, jp.location, jp.employment_type, jp.description,
                       jp.requirements, jp.application_deadline, jp.status, ep.organization_name,
                       jp.created_at, jp.updated_at,
                       (SELECT COUNT(*) FROM job_applications ja
                        WHERE ja.job_posting_id = jp.id AND ja.status = 'SUBMITTED') AS application_count,
                       FALSE AS applied, NULL AS application_id
                FROM job_postings jp JOIN employer_profiles ep ON ep.user_id = jp.employer_user_id
                WHERE jp.employer_user_id = ?
                ORDER BY jp.created_at DESC, jp.id DESC
                """, (rs, rowNum) -> jobView(rs), actor.id());
    }

    @Transactional
    public JobDtos.JobView createJob(AccountPrincipal actor, JobDtos.JobPostRequest request) {
        requireApprovedEmployer(actor);
        String type = normalizeEmploymentType(request.employmentType());
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO job_postings (employer_user_id, title, department, location, employment_type,
                                              description, requirements, application_deadline, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_REVIEW')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, actor.id());
            statement.setString(2, requiredText(request.title(), "Tên vị trí", 200));
            statement.setString(3, optionalText(request.department(), "Phòng ban", 180));
            statement.setString(4, optionalText(request.location(), "Địa điểm", 180));
            statement.setString(5, type);
            statement.setString(6, requiredText(request.description(), "Mô tả công việc", 12000));
            statement.setString(7, optionalText(request.requirements(), "Yêu cầu ứng viên", 12000));
            if (request.applicationDeadline() == null) statement.setNull(8, java.sql.Types.DATE);
            else statement.setObject(8, request.applicationDeadline());
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("Database did not return a job posting id");
        audit(actor.id(), "JOB_POSTING_CREATED", "JOB_POSTING", id.longValue(), "title,department,location,employmentType,description,requirements,applicationDeadline,status");
        return employerJobById(actor.id(), id.longValue());
    }

    @Transactional
    public JobDtos.JobView updateJob(AccountPrincipal actor, long jobId, JobDtos.JobPostRequest request) {
        requireApprovedEmployer(actor);
        String currentStatus = lockedOwnedJobStatus(actor.id(), jobId);
        if ("CLOSED".equals(currentStatus)) throw new ApiException(HttpStatus.CONFLICT, "JOB_ALREADY_CLOSED", "Tin đã đóng không thể chỉnh sửa.");
        String type = normalizeEmploymentType(request.employmentType());
        int changed = jdbc.update("""
                UPDATE job_postings SET title = ?, department = ?, location = ?, employment_type = ?,
                    description = ?, requirements = ?, application_deadline = ?, status = 'PENDING_REVIEW',
                    reviewed_by = NULL, reviewed_at = NULL, review_note = NULL, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND employer_user_id = ? AND status <> 'CLOSED'
                """, requiredText(request.title(), "Tên vị trí", 200),
                optionalText(request.department(), "Phòng ban", 180), optionalText(request.location(), "Địa điểm", 180),
                type, requiredText(request.description(), "Mô tả công việc", 12000),
                optionalText(request.requirements(), "Yêu cầu ứng viên", 12000), request.applicationDeadline(), jobId, actor.id());
        if (changed != 1) throw notFound("Không tìm thấy tin tuyển dụng trong tài khoản doanh nghiệp này.");
        audit(actor.id(), "JOB_POSTING_UPDATED_FOR_REVIEW", "JOB_POSTING", jobId,
                "title,department,location,employmentType,description,requirements,applicationDeadline,status");
        return employerJobById(actor.id(), jobId);
    }

    @Transactional
    public JobDtos.JobView closeJob(AccountPrincipal actor, long jobId) {
        requireApprovedEmployer(actor);
        String status = lockedOwnedJobStatus(actor.id(), jobId);
        if (!"PUBLISHED".equals(status)) throw new ApiException(HttpStatus.CONFLICT,
                "JOB_NOT_PUBLISHED", "Chỉ có thể đóng tin đang được công khai.");
        jdbc.update("""
                UPDATE job_postings SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND employer_user_id = ? AND status = 'PUBLISHED'
                """, jobId, actor.id());
        audit(actor.id(), "JOB_POSTING_CLOSED", "JOB_POSTING", jobId, "status,closedAt");
        return employerJobById(actor.id(), jobId);
    }

    @Transactional(readOnly = true)
    public List<JobDtos.EmployerApplicationView> employerApplications(AccountPrincipal actor) {
        requireApprovedEmployer(actor);
        return jdbc.query("""
                SELECT ja.id, jp.id AS job_id, jp.title AS job_title, ua.full_name AS applicant_name,
                       ja.status, ja.submitted_at, ja.cv_file_id
                FROM job_applications ja
                JOIN job_postings jp ON jp.id = ja.job_posting_id
                JOIN user_accounts ua ON ua.id = ja.applicant_account_id
                WHERE jp.employer_user_id = ?
                ORDER BY ja.submitted_at DESC, ja.id DESC
                """, (rs, rowNum) -> new JobDtos.EmployerApplicationView(
                rs.getLong("id"), rs.getLong("job_id"), rs.getString("job_title"),
                rs.getString("applicant_name"), rs.getString("status"), instant(rs.getTimestamp("submitted_at")),
                rs.getObject("cv_file_id") != null), actor.id());
    }

    @Transactional(readOnly = true)
    public CvDownload getApplicantCv(AccountPrincipal actor, long applicationId) {
        requireCandidate(actor);
        return readAuthorizedCv("""
                SELECT pf.storage_key FROM job_applications ja
                JOIN private_files pf ON pf.id = ja.cv_file_id AND pf.owner_account_id = ja.applicant_account_id
                WHERE ja.id = ? AND ja.applicant_account_id = ? AND pf.purpose = 'CV'
                """, applicationId, actor.id());
    }

    @Transactional(readOnly = true)
    public CvDownload getEmployerCv(AccountPrincipal actor, long applicationId) {
        requireApprovedEmployer(actor);
        return readAuthorizedCv("""
                SELECT pf.storage_key FROM job_applications ja
                JOIN job_postings jp ON jp.id = ja.job_posting_id
                JOIN private_files pf ON pf.id = ja.cv_file_id AND pf.owner_account_id = ja.applicant_account_id
                WHERE ja.id = ? AND jp.employer_user_id = ? AND pf.purpose = 'CV'
                """, applicationId, actor.id());
    }

    private JobDtos.ApplicationView applicationById(long applicationId, long accountId) {
        return jdbc.queryForObject("""
                SELECT ja.id, jp.id AS job_id, jp.title, ep.organization_name, ja.status,
                       ja.submitted_at, ja.cv_file_id
                FROM job_applications ja JOIN job_postings jp ON jp.id = ja.job_posting_id
                JOIN employer_profiles ep ON ep.user_id = jp.employer_user_id
                WHERE ja.id = ? AND ja.applicant_account_id = ?
                """, (rs, rowNum) -> new JobDtos.ApplicationView(rs.getLong("id"), rs.getLong("job_id"),
                rs.getString("title"), rs.getString("organization_name"), rs.getString("status"),
                instant(rs.getTimestamp("submitted_at")), rs.getObject("cv_file_id") != null), applicationId, accountId);
    }

    private JobDtos.JobView employerJobById(long employerId, long jobId) {
        return jdbc.queryForObject("""
                SELECT jp.id, jp.title, jp.department, jp.location, jp.employment_type, jp.description,
                       jp.requirements, jp.application_deadline, jp.status, ep.organization_name,
                       jp.created_at, jp.updated_at,
                       (SELECT COUNT(*) FROM job_applications ja
                        WHERE ja.job_posting_id = jp.id AND ja.status = 'SUBMITTED') AS application_count,
                       FALSE AS applied, NULL AS application_id
                FROM job_postings jp JOIN employer_profiles ep ON ep.user_id = jp.employer_user_id
                WHERE jp.id = ? AND jp.employer_user_id = ?
                """, (rs, rowNum) -> jobView(rs), jobId, employerId);
    }

    private String lockedOwnedJobStatus(long employerId, long jobId) {
        List<String> statuses = jdbc.query("""
                SELECT status FROM job_postings WHERE id = ? AND employer_user_id = ? FOR UPDATE
                """, (rs, rowNum) -> rs.getString(1), jobId, employerId);
        if (statuses.isEmpty()) throw notFound("Không tìm thấy tin tuyển dụng trong tài khoản doanh nghiệp này.");
        return statuses.get(0);
    }

    private CvDownload readAuthorizedCv(String sql, long applicationId, long actorId) {
        List<String> keys = jdbc.query(sql, (rs, rowNum) -> rs.getString("storage_key"), applicationId, actorId);
        if (keys.isEmpty()) throw notFound("Không tìm thấy CV trong phạm vi tài khoản.");
        Path file = privateDirectory.resolve(keys.get(0)).normalize();
        if (!file.startsWith(privateDirectory)) throw new IllegalStateException("Invalid private storage key");
        try {
            return new CvDownload(Files.readAllBytes(file));
        } catch (IOException exception) {
            throw notFound("Không tìm thấy tệp CV trong vùng lưu trữ riêng tư.");
        }
    }

    private StoredFile storeCv(long accountId, byte[] pdf) {
        String key = UUID.randomUUID() + ".pdf";
        Path target = privateDirectory.resolve(key).normalize();
        if (!target.startsWith(privateDirectory)) throw new IllegalStateException("Invalid private storage key");
        try {
            Files.createDirectories(privateDirectory);
            Path temporary = Files.createTempFile(privateDirectory, ".cv-", ".tmp");
            try {
                Files.write(temporary, pdf);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PRIVATE_STORAGE_FAILURE",
                    "Không thể lưu CV vào vùng lưu trữ riêng tư.");
        }
        registerRollbackCleanup(target);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO private_files (owner_account_id, purpose, storage_key, content_type, file_size)
                    VALUES (?, 'CV', ?, 'application/pdf', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, accountId);
            statement.setString(2, key);
            statement.setLong(3, pdf.length);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("Database did not return a private file id");
        return new StoredFile(id.longValue(), key);
    }

    private byte[] validateCv(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) throw invalid("Chọn tệp CV dạng PDF.");
        if (upload.getSize() > MAX_CV_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                "CV_TOO_LARGE", "CV không được vượt quá 10 MB.");
        try {
            byte[] bytes = upload.getBytes();
            if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-')
                throw invalid("Nội dung tệp không phải PDF hợp lệ.");
            return bytes;
        } catch (IOException exception) {
            throw invalid("Không thể đọc tệp CV đã chọn.");
        }
    }

    private void requireCandidate(AccountPrincipal actor) {
        boolean alumniRole = actor.roles().contains("ALUMNI");
        boolean studentRole = actor.roles().contains("STUDENT");
        if (!alumniRole && !studentRole) throw new ApiException(HttpStatus.FORBIDDEN,
                "JOB_CANDIDATE_REQUIRED", "Chức năng này chỉ dành cho cựu sinh viên hoặc sinh viên.");
        Integer eligible = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_accounts ua
                WHERE ua.id = ? AND ua.account_status = 'ACTIVE' AND (
                    (? = TRUE AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                                          WHERE ur.user_id = ua.id AND r.code = 'ALUMNI')
                     AND EXISTS (SELECT 1 FROM alumni_profiles ap WHERE ap.user_id = ua.id
                                AND ap.verification_status = 'VERIFIED' AND ap.archived_at IS NULL))
                    OR
                    (? = TRUE AND ua.email_verified_at IS NOT NULL
                     AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                                 WHERE ur.user_id = ua.id AND r.code = 'STUDENT'))
                )
                """, Integer.class, actor.id(), alumniRole, studentRole);
        if (eligible == null || eligible == 0) throw new ApiException(HttpStatus.FORBIDDEN,
                "JOB_CANDIDATE_NOT_VERIFIED", "Hồ sơ cựu sinh viên hoặc email sinh viên chưa đủ điều kiện ứng tuyển.");
    }

    private void requireApprovedEmployer(AccountPrincipal actor) {
        if (!actor.roles().contains("EMPLOYER")) throw new ApiException(HttpStatus.FORBIDDEN,
                "EMPLOYER_ROLE_REQUIRED", "Chức năng này chỉ dành cho tài khoản nhà tuyển dụng.");
        Integer approved = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_accounts ua JOIN employer_profiles ep ON ep.user_id = ua.id
                WHERE ua.id = ? AND ua.account_status = 'ACTIVE' AND ua.email_verified_at IS NOT NULL
                  AND ep.review_status = 'APPROVED'
                """, Integer.class, actor.id());
        if (approved == null || approved == 0) throw new ApiException(HttpStatus.FORBIDDEN,
                "EMPLOYER_NOT_APPROVED", "Doanh nghiệp phải xác minh email và được duyệt trước khi quản lý tin tuyển dụng.");
    }

    private static JobDtos.JobView jobView(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Date deadline = rs.getDate("application_deadline");
        long applicationIdValue = rs.getLong("application_id");
        Long applicationId = rs.wasNull() ? null : applicationIdValue;
        return new JobDtos.JobView(rs.getLong("id"), rs.getString("title"), rs.getString("department"),
                rs.getString("location"), rs.getString("employment_type"), rs.getString("description"),
                rs.getString("requirements"), deadline == null ? null : deadline.toLocalDate(), rs.getString("status"),
                rs.getString("organization_name"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")), rs.getLong("application_count"),
                rs.getBoolean("applied"), applicationId);
    }

    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

    private static String normalizeEmploymentType(String type) {
        if (type == null || type.isBlank()) return null;
        String value = type.trim().toUpperCase(Locale.ROOT);
        if (!EMPLOYMENT_TYPES.contains(value)) throw invalid("Hình thức làm việc không hợp lệ.");
        return value;
    }

    private static String optionalFilter(String value, String label, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw invalid(label + " không được vượt quá " + max + " ký tự.");
        return normalized;
    }

    private static String requiredText(String value, String label, int max) {
        if (value == null || value.isBlank()) throw invalid(label + " là bắt buộc.");
        String normalized = value.trim();
        if (normalized.length() > max) throw invalid(label + " không được vượt quá " + max + " ký tự.");
        return normalized;
    }

    private static String optionalText(String value, String label, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw invalid(label + " không được vượt quá " + max + " ký tự.");
        return normalized;
    }

    private void audit(long actorId, String action, String entityType, long entityId, String fields) {
        String[] names = fields.split(",");
        String jsonArray = "JSON_ARRAY(" + String.join(",", java.util.Collections.nCopies(names.length, "?")) + ")";
        Object[] arguments = new Object[4 + names.length];
        arguments[0] = actorId;
        arguments[1] = action;
        arguments[2] = entityType;
        arguments[3] = Long.toString(entityId);
        System.arraycopy(names, 0, arguments, 4, names.length);
        jdbc.update("INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields) "
                + "VALUES (?, ?, ?, ?, " + jsonArray + ")", arguments);
    }

    private void registerRollbackCleanup(Path current) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try { Files.deleteIfExists(current); } catch (IOException ignored) {
                        // A failed cleanup leaves only an inaccessible private orphan.
                    }
                }
            }
        });
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JOB_REQUEST", message);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "JOB_RESOURCE_NOT_FOUND", message);
    }
}
