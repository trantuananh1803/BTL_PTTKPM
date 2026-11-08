package vn.edu.phenikaa.alumni.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class EventDtos {
    private EventDtos() {}

    public record EventRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 20000) String description,
            @Size(max = 255) String location,
            @NotNull LocalDateTime startsAt,
            @NotNull LocalDateTime endsAt,
            @NotNull LocalDateTime registrationOpensAt,
            @NotNull LocalDateTime registrationClosesAt,
            @Positive Integer capacity) {}

    public record EventView(long id, String title, String description, String location,
                            LocalDateTime startsAt, LocalDateTime endsAt,
                            LocalDateTime registrationOpensAt, LocalDateTime registrationClosesAt,
                            Integer capacity, int registeredCount, String myRegistrationStatus,
                            boolean registrationOpen, boolean full, long createdBy,
                            String creatorName, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record EventPage(List<EventView> items, int page, int size, long totalElements) {}
    public record AccountOption(long id, String fullName, String email) {}
    public record AssigneeRequest(@NotNull List<@Positive Long> accountIds) {}
    public record AssigneeView(long id, String fullName, String email) {}
}
