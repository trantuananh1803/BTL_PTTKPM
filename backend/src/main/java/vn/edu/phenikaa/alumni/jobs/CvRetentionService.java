package vn.edu.phenikaa.alumni.jobs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class CvRetentionService {
    private record ExpiredCv(long applicationId, long fileId, String storageKey) {}

    private final JdbcTemplate jdbc;
    private final Path privateDirectory;

    public CvRetentionService(JdbcTemplate jdbc,
                              @Value("${app.storage.private-directory:./var/private-files}") String privateDirectory) {
        this.jdbc = jdbc;
        this.privateDirectory = Path.of(privateDirectory).toAbsolutePath().normalize();
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Bangkok")
    @Transactional
    public void deleteExpiredCvFiles() {
        List<ExpiredCv> expired = jdbc.query("""
                SELECT ja.id AS application_id, pf.id AS file_id, pf.storage_key
                FROM job_applications ja
                JOIN job_postings jp ON jp.id = ja.job_posting_id
                JOIN private_files pf ON pf.id = ja.cv_file_id AND pf.owner_account_id = ja.applicant_account_id
                WHERE jp.status = 'CLOSED'
                  AND jp.closed_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 12 MONTH)
                  AND pf.purpose = 'CV'
                FOR UPDATE
                """, (rs, rowNum) -> new ExpiredCv(rs.getLong("application_id"),
                rs.getLong("file_id"), rs.getString("storage_key")));
        for (ExpiredCv cv : expired) {
            jdbc.update("UPDATE job_applications SET cv_file_id = NULL WHERE id = ? AND cv_file_id = ?",
                    cv.applicationId(), cv.fileId());
            jdbc.update("DELETE FROM private_files WHERE id = ? AND purpose = 'CV'", cv.fileId());
            jdbc.update("""
                    INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields)
                    VALUES (NULL, 'CV_RETENTION_EXPIRED', 'JOB_APPLICATION', ?, JSON_ARRAY('cv'))
                    """, Long.toString(cv.applicationId()));
            Path file = privateDirectory.resolve(cv.storageKey()).normalize();
            if (file.startsWith(privateDirectory)) registerDeleteAfterCommit(file);
        }
    }

    private static void registerDeleteAfterCommit(Path file) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    try { Files.deleteIfExists(file); } catch (IOException ignored) {
                        // A failed cleanup leaves a private, inaccessible file for operational cleanup.
                    }
                }
            }
        });
    }
}
