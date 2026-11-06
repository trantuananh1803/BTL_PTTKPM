package vn.edu.phenikaa.alumni.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record SendRequest(
            @NotBlank @Pattern(regexp = "^[^\\r\\n]+$") @Size(max = 200) String title,
            @NotBlank @Size(max = 1000) String body,
            @NotBlank @Pattern(regexp = "ALUMNI|STUDENT|EVENT_REGISTRANTS") String audience,
            @NotBlank @Pattern(regexp = "IN_APP|EMAIL|SMS") String channel,
            @Positive Long eventId) {}

    public record NotificationView(long id, String title, String body, Long relatedEventId,
                                   String relatedEventTitle, LocalDateTime createdAt,
                                   LocalDateTime readAt) {}
    public record InboxPage(List<NotificationView> items, int page, int size, long totalElements,
                            long unreadCount) {}
    public record SendResult(long campaignId, String audience, String channel, String status,
                             int recipientCount, int sentCount, int failedCount) {}
}
