package vn.edu.phenikaa.alumni.events;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.api.EventDtos;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class EventService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private final JdbcTemplate jdbc;
    public EventService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public EventDtos.EventPage listPublic(int page, int size, String keyword, long accountId) {
        validatePage(page, size);
        String query = keyword == null ? "" : keyword.trim();
        String pattern = "%" + query + "%";
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM events e
                WHERE (? = '' OR e.title LIKE ? OR COALESCE(e.description, '') LIKE ? OR COALESCE(e.location, '') LIKE ?)
                """, Long.class, query, pattern, pattern, pattern);
        List<EventDtos.EventView> items = jdbc.query(eventSelect() + """
                WHERE (? = '' OR e.title LIKE ? OR COALESCE(e.description, '') LIKE ? OR COALESCE(e.location, '') LIKE ?)
                ORDER BY e.starts_at ASC, e.id ASC LIMIT ? OFFSET ?
                """, (rs, row) -> mapEvent(rs, accountId), accountId, query, pattern, pattern, pattern,
                size, (long) page * size);
        return new EventDtos.EventPage(items, page, size, total == null ? 0 : total);
    }

    public EventDtos.EventView getPublic(long eventId, long accountId) {
        List<EventDtos.EventView> rows = jdbc.query(eventSelect() + " WHERE e.id = ?",
                (rs, row) -> mapEvent(rs, accountId), accountId, eventId);
        if (rows.isEmpty()) throw notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện.");
        return rows.get(0);
    }

    public EventDtos.EventPage listManaged(int page, int size, AccountPrincipal principal) {
        validatePage(page, size);
        boolean admin = principal.roles().contains("ADMIN");
        String where = admin ? "" : " WHERE e.created_by = ? OR EXISTS (SELECT 1 FROM event_staff_assignments a WHERE a.event_id=e.id AND a.staff_account_id=?)";
        Object[] args = admin ? new Object[]{} : new Object[]{principal.id(), principal.id()};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM events e" + where, Long.class, args);
        String sql = eventSelect() + where + " ORDER BY e.starts_at DESC, e.id DESC LIMIT ? OFFSET ?";
        Object[] pageArgs = admin
                ? new Object[]{principal.id(), size, (long) page * size}
                : new Object[]{principal.id(), principal.id(), principal.id(), size, (long) page * size};
        List<EventDtos.EventView> items = jdbc.query(sql, (rs, row) -> mapEvent(rs, principal.id()), pageArgs);
        return new EventDtos.EventPage(items, page, size, total == null ? 0 : total);
    }

    @Transactional
    public EventDtos.EventView create(EventDtos.EventRequest request, AccountPrincipal principal) {
        validateRequest(request, null);
        jdbc.update("""
                INSERT INTO events (title, description, location, starts_at, ends_at,
                    registration_opens_at, registration_closes_at, capacity, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, clean(request.title()), cleanNullable(request.description()), cleanNullable(request.location()),
                timestamp(request.startsAt()), timestamp(request.endsAt()), timestamp(request.registrationOpensAt()),
                timestamp(request.registrationClosesAt()), request.capacity(), principal.id());
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        audit(principal.id(), "EVENT_CREATED", id);
        return getPublic(id, principal.id());
    }

    @Transactional
    public EventDtos.EventView update(long eventId, EventDtos.EventRequest request, AccountPrincipal principal) {
        validateRequest(request, eventId);
        requireCanManage(eventId, principal);
        jdbc.update("""
                UPDATE events SET title=?, description=?, location=?, starts_at=?, ends_at=?,
                    registration_opens_at=?, registration_closes_at=?, capacity=?, updated_at=CURRENT_TIMESTAMP(6)
                WHERE id=?
                """, clean(request.title()), cleanNullable(request.description()), cleanNullable(request.location()),
                timestamp(request.startsAt()), timestamp(request.endsAt()), timestamp(request.registrationOpensAt()),
                timestamp(request.registrationClosesAt()), request.capacity(), eventId);
        audit(principal.id(), "EVENT_UPDATED", eventId);
        return getPublic(eventId, principal.id());
    }

    @Transactional
    public EventDtos.EventView register(long eventId, long accountId) {
        EventLock event = lockEvent(eventId);
        LocalDateTime now = now();
        if (now.isBefore(event.opensAt()) || !now.isBefore(event.closesAt()))
            throw conflict("REGISTRATION_CLOSED", "Sự kiện chưa mở đăng ký hoặc đã hết hạn đăng ký.");
        String existing = jdbc.query("SELECT status FROM event_registrations WHERE event_id=? AND account_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), eventId, accountId).stream().findFirst().orElse(null);
        if ("REGISTERED".equals(existing))
            throw conflict("ALREADY_REGISTERED", "Tài khoản đã đăng ký sự kiện này.");
        Integer registered = jdbc.queryForObject("SELECT COUNT(*) FROM event_registrations WHERE event_id=? AND status='REGISTERED'",
                Integer.class, eventId);
        if (event.capacity() != null && registered != null && registered >= event.capacity())
            throw conflict("EVENT_FULL", "Sự kiện đã đủ sức chứa.");
        if (existing == null) {
            jdbc.update("INSERT INTO event_registrations (event_id, account_id) VALUES (?, ?)", eventId, accountId);
        } else {
            jdbc.update("UPDATE event_registrations SET status='REGISTERED', registered_at=CURRENT_TIMESTAMP(6), cancelled_at=NULL WHERE event_id=? AND account_id=?",
                    eventId, accountId);
        }
        systemNotice(accountId, eventId, "Đăng ký sự kiện thành công", "Bạn đã đăng ký tham dự: " + event.title());
        return getPublic(eventId, accountId);
    }

    @Transactional
    public void cancel(long eventId, long accountId) {
        EventLock event = lockEvent(eventId);
        LocalDateTime now = now();
        if (!now.isBefore(event.closesAt()))
            throw conflict("CANCELLATION_CLOSED", "Chỉ có thể hủy đăng ký trước thời điểm đóng đăng ký.");
        List<String> states = jdbc.query("SELECT status FROM event_registrations WHERE event_id=? AND account_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), eventId, accountId);
        if (states.isEmpty()) throw notFound("REGISTRATION_NOT_FOUND", "Không tìm thấy đăng ký sự kiện.");
        if ("CANCELLED".equals(states.get(0))) return;
        jdbc.update("UPDATE event_registrations SET status='CANCELLED', cancelled_at=CURRENT_TIMESTAMP(6) WHERE event_id=? AND account_id=?",
                eventId, accountId);
        systemNotice(accountId, eventId, "Đã hủy đăng ký", "Bạn đã hủy đăng ký tham dự: " + event.title());
    }

    public List<EventDtos.AccountOption> staffOptions() {
        return jdbc.query("""
                SELECT DISTINCT ua.id, ua.full_name, ua.email
                FROM user_accounts ua
                JOIN user_roles ur ON ur.user_id=ua.id
                JOIN roles r ON r.id=ur.role_id AND r.code='FACULTY_STAFF'
                WHERE ua.account_status='ACTIVE' AND ua.email_verified_at IS NOT NULL
                ORDER BY ua.full_name, ua.id
                """, (rs, row) -> new EventDtos.AccountOption(rs.getLong("id"), rs.getString("full_name"), rs.getString("email")));
    }

    public List<EventDtos.AssigneeView> assignees(long eventId) {
        requireEvent(eventId);
        return jdbc.query("""
                SELECT ua.id, ua.full_name, ua.email FROM event_staff_assignments a
                JOIN user_accounts ua ON ua.id=a.staff_account_id
                WHERE a.event_id=? ORDER BY ua.full_name, ua.id
                """, (rs, row) -> new EventDtos.AssigneeView(rs.getLong("id"), rs.getString("full_name"), rs.getString("email")), eventId);
    }

    @Transactional
    public List<EventDtos.AssigneeView> assign(long eventId, List<Long> accountIds, long adminId) {
        requireEvent(eventId);
        Set<Long> unique = new HashSet<>(accountIds);
        if (unique.size() != accountIds.size()) throw bad("DUPLICATE_ASSIGNEE", "Danh sách cán bộ có phần tử trùng.");
        for (Long id : unique) {
            Integer valid = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_accounts ua
                    JOIN user_roles ur ON ur.user_id=ua.id JOIN roles r ON r.id=ur.role_id
                    WHERE ua.id=? AND ua.account_status='ACTIVE' AND r.code='FACULTY_STAFF'
                    """, Integer.class, id);
            if (valid == null || valid == 0) throw bad("STAFF_ACCOUNT_REQUIRED", "Chỉ có thể giao sự kiện cho tài khoản cán bộ đang hoạt động.");
        }
        jdbc.update("DELETE FROM event_staff_assignments WHERE event_id=?", eventId);
        for (Long id : unique)
            jdbc.update("INSERT INTO event_staff_assignments (event_id, staff_account_id, assigned_by) VALUES (?, ?, ?)", eventId, id, adminId);
        jdbc.update("""
                INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields)
                VALUES (?, 'EVENT_ASSIGNEES_UPDATED', 'EVENT', ?, JSON_ARRAY('assignees'))
                """, adminId, Long.toString(eventId));
        return assignees(eventId);
    }

    public boolean canManage(long eventId, AccountPrincipal principal) {
        if (principal.roles().contains("ADMIN")) return true;
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM events e WHERE e.id=? AND
                    (e.created_by=? OR EXISTS (SELECT 1 FROM event_staff_assignments a WHERE a.event_id=e.id AND a.staff_account_id=?))
                """, Integer.class, eventId, principal.id(), principal.id());
        return found != null && found > 0;
    }

    public boolean exists(long eventId) {
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE id=?", Integer.class, eventId);
        return found != null && found > 0;
    }

    private void requireCanManage(long eventId, AccountPrincipal principal) {
        requireEvent(eventId);
        if (!canManage(eventId, principal)) throw new ApiException(HttpStatus.FORBIDDEN, "EVENT_SCOPE_FORBIDDEN", "Không có quyền quản lý sự kiện này.");
    }

    private EventLock lockEvent(long eventId) {
        List<EventLock> rows = jdbc.query("SELECT title, registration_opens_at, registration_closes_at, capacity FROM events WHERE id=? FOR UPDATE",
                (rs, row) -> new EventLock(rs.getString("title"), local(rs.getTimestamp("registration_opens_at")),
                        local(rs.getTimestamp("registration_closes_at")), (Integer) rs.getObject("capacity")), eventId);
        if (rows.isEmpty()) throw notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện.");
        return rows.get(0);
    }

    private void requireEvent(long eventId) {
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE id=?", Integer.class, eventId);
        if (found == null || found == 0) throw notFound("EVENT_NOT_FOUND", "Không tìm thấy sự kiện.");
    }

    private String eventSelect() {
        return """
                SELECT e.id, e.title, e.description, e.location, e.starts_at, e.ends_at,
                    e.registration_opens_at, e.registration_closes_at, e.capacity, e.created_by,
                    e.created_at, e.updated_at, ua.full_name AS creator_name,
                    (SELECT COUNT(*) FROM event_registrations r WHERE r.event_id=e.id AND r.status='REGISTERED') AS registered_count,
                    (SELECT r.status FROM event_registrations r WHERE r.event_id=e.id AND r.account_id=? LIMIT 1) AS my_registration_status
                FROM events e JOIN user_accounts ua ON ua.id=e.created_by
                """;
    }

    private EventDtos.EventView mapEvent(java.sql.ResultSet rs, long accountId) throws java.sql.SQLException {
        LocalDateTime opens = local(rs.getTimestamp("registration_opens_at"));
        LocalDateTime closes = local(rs.getTimestamp("registration_closes_at"));
        LocalDateTime starts = local(rs.getTimestamp("starts_at"));
        LocalDateTime ends = local(rs.getTimestamp("ends_at"));
        Integer capacity = (Integer) rs.getObject("capacity");
        int registered = rs.getInt("registered_count");
        LocalDateTime now = now();
        return new EventDtos.EventView(rs.getLong("id"), rs.getString("title"), rs.getString("description"),
                rs.getString("location"), starts, ends, opens, closes, capacity, registered,
                rs.getString("my_registration_status"), !now.isBefore(opens) && now.isBefore(closes),
                capacity != null && registered >= capacity, rs.getLong("created_by"), rs.getString("creator_name"),
                local(rs.getTimestamp("created_at")), local(rs.getTimestamp("updated_at")));
    }

    private void validateRequest(EventDtos.EventRequest request, Long eventId) {
        if (!request.endsAt().isAfter(request.startsAt()) ||
                !request.registrationOpensAt().isBefore(request.registrationClosesAt()) ||
                request.registrationClosesAt().isAfter(request.startsAt()))
            throw bad("EVENT_TIME_RANGE_INVALID", "Thời gian sự kiện và khung đăng ký chưa hợp lệ.");
        Integer registrations = eventId == null ? 0 : jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_registrations WHERE event_id=? AND status='REGISTERED'",
                Integer.class, eventId);
        if (request.capacity() != null && registrations != null && registrations > request.capacity())
            throw bad("EVENT_CAPACITY_TOO_SMALL", "Sức chứa không được thấp hơn số lượt đăng ký hiện tại.");
    }

    private void systemNotice(long accountId, long eventId, String title, String body) {
        jdbc.update("INSERT INTO notifications (recipient_account_id, related_event_id, title, body) VALUES (?, ?, ?, ?)",
                accountId, eventId, title, body);
    }

    private void audit(long actorId, String action, long eventId) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields)
                VALUES (?, ?, 'EVENT', ?, JSON_ARRAY('title','description','location','starts_at','ends_at','registration_opens_at','registration_closes_at','capacity'))
                """, actorId, action, Long.toString(eventId));
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw bad("PAGE_RANGE_INVALID", "Trang hoặc số dòng yêu cầu không hợp lệ.");
    }
    private static LocalDateTime now() { return LocalDateTime.now(BUSINESS_ZONE); }
    private static LocalDateTime local(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private static Timestamp timestamp(LocalDateTime value) { return Timestamp.valueOf(value); }
    private static String clean(String value) { return value.trim(); }
    private static String cleanNullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }

    private record EventLock(String title, LocalDateTime opensAt, LocalDateTime closesAt, Integer capacity) {}
}
