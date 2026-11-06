package vn.edu.phenikaa.alumni.api;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.phenikaa.alumni.events.EventService;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class EventManagementController {
    private final EventService events;

    public EventManagementController(EventService events) { this.events = events; }

    @GetMapping("/staff/events")
    @PreAuthorize("hasAnyRole('FACULTY_STAFF','ADMIN')")
    EventDtos.EventPage managed(@AuthenticationPrincipal AccountPrincipal principal,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return events.listManaged(page, size, principal);
    }

    @PostMapping("/staff/events")
    @PreAuthorize("hasAnyRole('FACULTY_STAFF','ADMIN')")
    EventDtos.EventView create(@Valid @RequestBody EventDtos.EventRequest request,
                               @AuthenticationPrincipal AccountPrincipal principal) {
        return events.create(request, principal);
    }

    @PatchMapping("/staff/events/{eventId}")
    @PreAuthorize("hasAnyRole('FACULTY_STAFF','ADMIN')")
    EventDtos.EventView update(@PathVariable long eventId,
                               @Valid @RequestBody EventDtos.EventRequest request,
                               @AuthenticationPrincipal AccountPrincipal principal) {
        return events.update(eventId, request, principal);
    }

    @GetMapping("/admin/events/staff-options")
    @PreAuthorize("hasRole('ADMIN')")
    List<EventDtos.AccountOption> staffOptions() { return events.staffOptions(); }

    @GetMapping("/admin/events/{eventId}/assignees")
    @PreAuthorize("hasRole('ADMIN')")
    List<EventDtos.AssigneeView> assignees(@PathVariable long eventId) {
        return events.assignees(eventId);
    }

    @PutMapping("/admin/events/{eventId}/assignees")
    @PreAuthorize("hasRole('ADMIN')")
    List<EventDtos.AssigneeView> assign(@PathVariable long eventId,
                                        @Valid @RequestBody EventDtos.AssigneeRequest request,
                                        @AuthenticationPrincipal AccountPrincipal principal) {
        return events.assign(eventId, request.accountIds(), principal.id());
    }
}
