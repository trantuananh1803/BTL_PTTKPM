package vn.edu.phenikaa.alumni.api;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.phenikaa.alumni.notifications.NotificationService;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping("/me/notifications")
    @PreAuthorize("isAuthenticated()")
    NotificationDtos.InboxPage inbox(@AuthenticationPrincipal AccountPrincipal principal,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return notifications.inbox(principal.id(), page, size);
    }

    @PatchMapping("/me/notifications/{notificationId}/read")
    @PreAuthorize("isAuthenticated()")
    void read(@PathVariable long notificationId, @AuthenticationPrincipal AccountPrincipal principal) {
        notifications.markRead(notificationId, principal.id());
    }

    @PostMapping("/staff/notifications")
    @PreAuthorize("hasAnyRole('FACULTY_STAFF','ADMIN')")
    NotificationDtos.SendResult send(@Valid @RequestBody NotificationDtos.SendRequest request,
                                     @AuthenticationPrincipal AccountPrincipal principal) {
        return notifications.send(request, principal);
    }
}
