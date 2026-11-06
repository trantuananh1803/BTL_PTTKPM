package vn.edu.phenikaa.alumni.notifications;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.api.EmailDeliveryException;
import vn.edu.phenikaa.alumni.api.NotificationDtos;
import vn.edu.phenikaa.alumni.events.EventService;
import vn.edu.phenikaa.alumni.mail.EmailDeliveryPort;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EmailDeliveryPort emailDelivery;
    private final EventService events;

    public NotificationService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                               EmailDeliveryPort emailDelivery, EventService events) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.emailDelivery = emailDelivery;
        this.events = events;
    }

    public NotificationDtos.InboxPage inbox(long accountId, int page, int size) {
        validatePage(page, size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_account_id=?",
                Long.class, accountId);
        Long unread = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_account_id=? AND read_at IS NULL",
                Long.class, accountId);
        List<NotificationDtos.NotificationView> items = jdbc.query("""
                SELECT n.id, n.title, n.body, n.related_event_id, e.title AS related_event_title, n.created_at, n.read_at
                FROM notifications n LEFT JOIN events e ON e.id=n.related_event_id
                WHERE n.recipient_account_id=? ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new NotificationDtos.NotificationView(rs.getLong("id"), rs.getString("title"),
                rs.getString("body"), nullableLong(rs, "related_event_id"), rs.getString("related_event_title"),
                local(rs.getTimestamp("created_at")), local(rs.getTimestamp("read_at"))),
                accountId, size, (long) page * size);
        return new NotificationDtos.InboxPage(items, page, size, total == null ? 0 : total, unread == null ? 0 : unread);
    }

    public void markRead(long notificationId, long accountId) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE id=? AND recipient_account_id=?",
                Integer.class, notificationId, accountId);
        if (exists == null || exists == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Không tìm thấy thông báo.");
        jdbc.update("""
                UPDATE notifications SET read_at=COALESCE(read_at, CURRENT_TIMESTAMP(6))
                WHERE id=? AND recipient_account_id=?
                """, notificationId, accountId);
    }

    public NotificationDtos.SendResult send(NotificationDtos.SendRequest request, AccountPrincipal principal) {
        String audience = request.audience().trim().toUpperCase();
        String channel = request.channel().trim().toUpperCase();
        if ("SMS".equals(channel))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SMS_NOT_AVAILABLE", "Kênh SMS là phần mở rộng và chưa được bật.");
        if (!List.of("IN_APP", "EMAIL").contains(channel))
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOTIFICATION_CHANNEL_INVALID", "Kênh thông báo không hợp lệ.");
        Long eventId = request.eventId();
        if ("EVENT_REGISTRANTS".equals(audience) && eventId == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "EVENT_REQUIRED", "Cần chọn sự kiện cho nhóm người đã đăng ký.");
        if (eventId != null) {
            if (!events.exists(eventId)) throw new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Không tìm thấy sự kiện.");
            if (!events.canManage(eventId, principal))
                throw new ApiException(HttpStatus.FORBIDDEN, "EVENT_SCOPE_FORBIDDEN", "Không có quyền gửi thông báo cho sự kiện này.");
        }
        List<Recipient> recipients = recipients(audience, eventId);
        if ("IN_APP".equals(channel)) return createInAppCampaign(request, principal.id(), audience, recipients);
        return deliverEmailCampaign(request, principal.id(), audience, recipients);
    }

    private NotificationDtos.SendResult createInAppCampaign(NotificationDtos.SendRequest request, long senderId,
                                                             String audience, List<Recipient> recipients) {
        long campaignId = transaction.execute(status -> {
            long id = insertCampaign(request, senderId, audience, "IN_APP", "DELIVERED", request.eventId(),
                    recipients.size(), recipients.size(), 0);
            for (Recipient recipient : recipients) {
                jdbc.update("""
                        INSERT INTO notifications (campaign_id, recipient_account_id, related_event_id, title, body)
                        VALUES (?, ?, ?, ?, ?)
                        """, id, recipient.id(), request.eventId(), clean(request.title()), clean(request.body()));
            }
            audit(senderId, id, "NOTIFICATION_IN_APP_SENT");
            return id;
        });
        return new NotificationDtos.SendResult(campaignId, audience, "IN_APP", "DELIVERED",
                recipients.size(), recipients.size(), 0);
    }

    private NotificationDtos.SendResult deliverEmailCampaign(NotificationDtos.SendRequest request, long senderId,
                                                              String audience, List<Recipient> recipients) {
        Long campaignId = transaction.execute(status -> insertCampaign(request, senderId, audience, "EMAIL",
                recipients.isEmpty() ? "DELIVERED" : "SENDING", request.eventId(), recipients.size(), 0, 0));
        int sent = 0;
        int failed = 0;
        for (Recipient recipient : recipients) {
            try {
                emailDelivery.sendMessage(recipient.email(), clean(request.title()), clean(request.body()));
                sent++;
            } catch (EmailDeliveryException deliveryFailure) {
                failed++;
            }
        }
        String result = failed == 0 ? "DELIVERED" : sent == 0 ? "FAILED" : "PARTIAL";
        int sentCount = sent;
        int failedCount = failed;
        transaction.execute(status -> {
            jdbc.update("""
                    UPDATE notification_campaigns SET status=?, sent_count=?, failed_count=?, completed_at=CURRENT_TIMESTAMP(6)
                    WHERE id=?
                    """, result, sentCount, failedCount, campaignId);
            audit(senderId, campaignId, "NOTIFICATION_EMAIL_DISPATCHED");
            return null;
        });
        return new NotificationDtos.SendResult(campaignId, audience, "EMAIL", result,
                recipients.size(), sentCount, failedCount);
    }

    private List<Recipient> recipients(String audience, Long eventId) {
        if ("ALUMNI".equals(audience) || "STUDENT".equals(audience)) {
            return jdbc.query("""
                    SELECT ua.id, ua.email FROM user_accounts ua
                    WHERE ua.account_status='ACTIVE' AND ua.email_verified_at IS NOT NULL
                      AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                                  WHERE ur.user_id=ua.id AND r.code=?)
                    ORDER BY ua.id
                    """, (rs, row) -> new Recipient(rs.getLong("id"), rs.getString("email")), audience);
        }
        if ("EVENT_REGISTRANTS".equals(audience)) {
            return jdbc.query("""
                    SELECT ua.id, ua.email FROM user_accounts ua
                    JOIN event_registrations er ON er.account_id=ua.id AND er.status='REGISTERED'
                    WHERE er.event_id=? AND ua.account_status='ACTIVE' AND ua.email_verified_at IS NOT NULL
                    ORDER BY ua.id
                    """, (rs, row) -> new Recipient(rs.getLong("id"), rs.getString("email")), eventId);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "NOTIFICATION_AUDIENCE_INVALID", "Đối tượng nhận thông báo không hợp lệ.");
    }

    private long insertCampaign(NotificationDtos.SendRequest request, long senderId, String audience,
                                String channel, String status, Long eventId, int recipientCount,
                                int sentCount, int failedCount) {
        String sql = """
                INSERT INTO notification_campaigns (created_by, title, body, audience, channel, related_event_id,
                    status, recipient_count, sent_count, failed_count, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keys = new GeneratedKeyHolder();
        LocalDateTime completedAt = "SENDING".equals(status) ? null : LocalDateTime.now(ZoneId.of("Asia/Bangkok"));
        jdbc.update((Connection connection) -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, senderId);
            statement.setString(2, clean(request.title()));
            statement.setString(3, clean(request.body()));
            statement.setString(4, audience);
            statement.setString(5, channel);
            if (eventId == null) statement.setNull(6, java.sql.Types.BIGINT); else statement.setLong(6, eventId);
            statement.setString(7, status);
            statement.setInt(8, recipientCount);
            statement.setInt(9, sentCount);
            statement.setInt(10, failedCount);
            if (completedAt == null) statement.setNull(11, java.sql.Types.TIMESTAMP); else statement.setTimestamp(11, Timestamp.valueOf(completedAt));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Notification campaign insert did not return an id");
        return key.longValue();
    }

    private void audit(long actorId, long campaignId, String action) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields)
                VALUES (?, ?, 'NOTIFICATION_CAMPAIGN', ?, JSON_ARRAY('title','audience','channel','recipient_count','sent_count','failed_count'))
                """, actorId, action, Long.toString(campaignId));
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGE_RANGE_INVALID", "Trang hoặc số dòng yêu cầu không hợp lệ.");
    }
    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }
    private static LocalDateTime local(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private static String clean(String value) { return value.trim(); }
    private record Recipient(long id, String email) {}
}
