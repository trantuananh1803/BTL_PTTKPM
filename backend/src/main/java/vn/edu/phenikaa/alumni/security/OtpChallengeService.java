package vn.edu.phenikaa.alumni.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.mail.EmailDeliveryPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class OtpChallengeService {
    public enum Purpose { REGISTER, PASSWORD_RECOVERY }
    public enum Delivery { SENT, NOT_SENT, FAILED }
    public record Verification(long accountId) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ADDRESS_SENDS_PER_HOUR = 5;
    private static final Duration OTP_TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final EmailDeliveryPort emailDelivery;
    private final int sendsPerIpPerHour;

    public OtpChallengeService(JdbcTemplate jdbc,
                               org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                               EmailDeliveryPort emailDelivery,
                               @Value("${app.security.otp-max-sends-per-ip-per-hour:20}") int sendsPerIpPerHour) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.emailDelivery = emailDelivery;
        this.sendsPerIpPerHour = sendsPerIpPerHour;
    }

    @Transactional
    public Delivery issue(Long accountId, String email, String requesterIp, Purpose purpose) {
        String normalizedEmail = normalizeEmail(email);
        String recipientHash = sha256(normalizedEmail);
        String ipHash = sha256(requesterIp == null ? "unknown" : requesterIp);
        List<String> lockNames = java.util.stream.Stream.of(
                        "otp-recipient-" + recipientHash.substring(0, 16),
                        "otp-ip-" + ipHash.substring(0, 16))
                .sorted().toList();
        List<String> acquiredLocks = new java.util.ArrayList<>();
        try {
            for (String lockName : lockNames) {
                Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 2)", Integer.class, lockName);
                if (acquired == null || acquired != 1) {
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED",
                            "Vui lòng chờ trước khi yêu cầu mã mới.");
                }
                acquiredLocks.add(lockName);
            }
            return issueWithRateLocks(accountId, normalizedEmail, purpose, recipientHash, ipHash);
        } finally {
            for (int index = acquiredLocks.size() - 1; index >= 0; index--) {
                jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, acquiredLocks.get(index));
            }
        }
    }

    private Delivery issueWithRateLocks(Long accountId, String normalizedEmail, Purpose purpose,
                                        String recipientHash, String ipHash) {

        Integer addressSends = jdbc.queryForObject("""
                SELECT COUNT(*) FROM otp_challenges
                WHERE recipient_hash = ? AND created_at >= CURRENT_TIMESTAMP(6) - INTERVAL 1 HOUR
                """, Integer.class, recipientHash);
        Integer ipSends = jdbc.queryForObject("""
                SELECT COUNT(*) FROM otp_challenges
                WHERE requester_ip_hash = ? AND created_at >= CURRENT_TIMESTAMP(6) - INTERVAL 1 HOUR
                """, Integer.class, ipHash);
        Integer accountSends = accountId == null ? 0 : jdbc.queryForObject("""
                SELECT COUNT(*) FROM otp_challenges
                WHERE account_id = ? AND purpose = ? AND created_at >= CURRENT_TIMESTAMP(6) - INTERVAL 1 HOUR
                """, Integer.class, accountId, purpose.name());
        Integer sentRecently = jdbc.queryForObject("""
                SELECT COUNT(*) FROM otp_challenges
                WHERE recipient_hash = ? AND purpose = ? AND sent_at >= CURRENT_TIMESTAMP(6) - INTERVAL 60 SECOND
                """, Integer.class, recipientHash, purpose.name());

        if ((addressSends != null && addressSends >= ADDRESS_SENDS_PER_HOUR)
                || (ipSends != null && ipSends >= sendsPerIpPerHour)
                || (accountSends != null && accountSends >= ADDRESS_SENDS_PER_HOUR)
                || (sentRecently != null && sentRecently > 0)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED",
                    "Vui lòng chờ trước khi yêu cầu mã mới.");
        }

        jdbc.update("""
                UPDATE otp_challenges SET invalidated_at = CURRENT_TIMESTAMP(6)
                WHERE recipient_hash = ? AND purpose = ? AND consumed_at IS NULL AND invalidated_at IS NULL
                """, recipientHash, purpose.name());

        String otp = String.format(Locale.ROOT, "%08d", RANDOM.nextInt(100_000_000));
        String codeHash = accountId == null ? null : passwordEncoder.encode(otp);
        jdbc.update("""
                INSERT INTO otp_challenges
                    (account_id, recipient_hash, requester_ip_hash, purpose, code_hash,
                     delivery_status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6) + INTERVAL 10 MINUTE)
                """, accountId, recipientHash, ipHash, purpose.name(), codeHash,
                accountId == null ? "NOT_SENT" : "PENDING");

        if (accountId == null) return Delivery.NOT_SENT;
        try {
            emailDelivery.sendOtp(normalizedEmail, otp, purpose.name());
            jdbc.update("""
                    UPDATE otp_challenges SET delivery_status = 'SENT', sent_at = CURRENT_TIMESTAMP(6)
                    WHERE recipient_hash = ? AND purpose = ? AND delivery_status = 'PENDING'
                      AND invalidated_at IS NULL ORDER BY id DESC LIMIT 1
                    """, recipientHash, purpose.name());
            return Delivery.SENT;
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE otp_challenges SET delivery_status = 'FAILED', sent_at = CURRENT_TIMESTAMP(6)
                    WHERE recipient_hash = ? AND purpose = ? AND delivery_status = 'PENDING'
                      AND invalidated_at IS NULL ORDER BY id DESC LIMIT 1
                    """, recipientHash, purpose.name());
            return Delivery.FAILED;
        }
    }

    public Verification verify(String email, String code, Purpose purpose) {
        String recipientHash = sha256(normalizeEmail(email));
        List<OtpRow> rows = jdbc.query("""
                SELECT id, account_id, code_hash, attempts FROM otp_challenges
                WHERE recipient_hash = ? AND purpose = ? AND delivery_status = 'SENT'
                  AND consumed_at IS NULL AND invalidated_at IS NULL
                  AND expires_at > CURRENT_TIMESTAMP(6)
                ORDER BY id DESC LIMIT 1 FOR UPDATE
                """, (rs, rowNum) -> new OtpRow(rs.getLong("id"), rs.getLong("account_id"),
                rs.getString("code_hash"), rs.getInt("attempts")), recipientHash, purpose.name());
        if (rows.isEmpty()) throw invalidOtp();

        OtpRow row = rows.get(0);
        if (row.attempts() >= 5 || row.codeHash() == null
                || !passwordEncoder.matches(code, row.codeHash())) {
            jdbc.update("""
                    UPDATE otp_challenges SET attempts = LEAST(attempts + 1, 5),
                      invalidated_at = CASE WHEN attempts + 1 >= 5 THEN CURRENT_TIMESTAMP(6) ELSE invalidated_at END
                    WHERE id = ?
                    """, row.id());
            throw invalidOtp();
        }
        jdbc.update("UPDATE otp_challenges SET consumed_at = CURRENT_TIMESTAMP(6) WHERE id = ?", row.id());
        return new Verification(row.accountId());
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidOtp() {
        return new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID_OR_EXPIRED",
                "Mã xác minh không hợp lệ hoặc đã hết hạn.");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record OtpRow(long id, long accountId, String codeHash, int attempts) {}
}
