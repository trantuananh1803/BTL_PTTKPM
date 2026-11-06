package vn.edu.phenikaa.alumni.api;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;
import vn.edu.phenikaa.alumni.events.EventService;

@RestController
@RequestMapping("/api/v1/events")
@PreAuthorize("isAuthenticated()")
public class EventController {
    private final EventService events;

    public EventController(EventService events) { this.events = events; }

    @GetMapping
    EventDtos.EventPage list(@AuthenticationPrincipal AccountPrincipal principal,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size,
                             @RequestParam(required = false) String keyword) {
        return events.listPublic(page, size, keyword, principal.id());
    }

    @GetMapping("/{eventId}")
    EventDtos.EventView get(@PathVariable long eventId,
                            @AuthenticationPrincipal AccountPrincipal principal) {
        return events.getPublic(eventId, principal.id());
    }

    @PostMapping("/{eventId}/registrations")
    EventDtos.EventView register(@PathVariable long eventId,
                                 @AuthenticationPrincipal AccountPrincipal principal) {
        return events.register(eventId, principal.id());
    }

    @DeleteMapping("/{eventId}/registrations/me")
    void cancel(@PathVariable long eventId,
                @AuthenticationPrincipal AccountPrincipal principal) {
        events.cancel(eventId, principal.id());
    }
}
