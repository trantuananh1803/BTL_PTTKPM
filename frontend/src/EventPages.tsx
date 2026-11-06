import { useEffect, useMemo, useState, type FormEvent } from "react";
import { apiRequest, errorMessage, requestCsrf } from "./api";
import "./EventPages.css";

type Role = "ALUMNI" | "STUDENT" | "EMPLOYER" | "FACULTY_STAFF" | "ADMIN";
type EventItem = {
  id: number; title: string; description: string | null; location: string | null;
  startsAt: string; endsAt: string; registrationOpensAt: string; registrationClosesAt: string;
  capacity: number | null; registeredCount: number; myRegistrationStatus: "REGISTERED" | "CANCELLED" | null;
  registrationOpen: boolean; full: boolean; createdBy: number; creatorName: string;
};
type Page<T> = { items: T[]; page: number; size: number; totalElements: number };
type Notice = { id: number; title: string; body: string; relatedEventId: number | null; relatedEventTitle: string | null; createdAt: string; readAt: string | null };
type Inbox = Page<Notice> & { unreadCount: number };
type StaffOption = { id: number; fullName: string; email: string };
type SendResult = { campaignId: number; audience: string; channel: string; status: string; recipientCount: number; sentCount: number; failedCount: number };
type EventForm = { title: string; description: string; location: string; startsAt: string; endsAt: string; registrationOpensAt: string; registrationClosesAt: string; capacity: string };

const emptyEvent = (): EventForm => ({ title: "", description: "", location: "", startsAt: "", endsAt: "", registrationOpensAt: "", registrationClosesAt: "", capacity: "" });
const dateInput = (value: string) => value ? value.slice(0, 16) : "";
const localTime = (value: string) => value ? value.length === 16 ? `${value}:00` : value : null;
const dateLabel = (value: string) => new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
const deliveryLabel: Record<string, string> = { DELIVERED: "Đã giao", PARTIAL: "Giao một phần", FAILED: "Giao thất bại" };

export function EventsPage({ roles, previewMode, initialTab = "events" }: { roles: Role[]; previewMode: boolean; initialTab?: "events" | "inbox" }) {
  const canManage = roles.includes("FACULTY_STAFF") || roles.includes("ADMIN");
  const isAdmin = roles.includes("ADMIN");
  const [tab, setTab] = useState<"events" | "inbox" | "manage" | "compose">(initialTab);
  const [events, setEvents] = useState<EventItem[]>([]);
  const [managedEvents, setManagedEvents] = useState<EventItem[]>([]);
  const [inbox, setInbox] = useState<Inbox | null>(null);
  const [staffOptions, setStaffOptions] = useState<StaffOption[]>([]);
  const [assignees, setAssignees] = useState<number[]>([]);
  const [selectedEvent, setSelectedEvent] = useState<EventItem | null>(null);
  const [assignmentEvent, setAssignmentEvent] = useState<EventItem | null>(null);
  const [form, setForm] = useState<EventForm>(emptyEvent());
  const [sendForm, setSendForm] = useState({ title: "", body: "", audience: "ALUMNI", channel: "IN_APP", eventId: "" });
  const [busy, setBusy] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [inboxLoadFailed, setInboxLoadFailed] = useState(false);
  const [notice, setNotice] = useState("");

  const tabs = useMemo(() => [
    { id: "events" as const, label: "Sự kiện" },
    { id: "inbox" as const, label: `Thông báo${inbox?.unreadCount ? ` (${inbox.unreadCount})` : ""}` },
    ...(canManage ? [{ id: "manage" as const, label: "Quản lý sự kiện" }, { id: "compose" as const, label: "Gửi thông báo" }] : []),
  ], [canManage, inbox?.unreadCount]);

  async function loadPublicEvents() {
    if (previewMode) return;
    setLoading(true); setError("");
    try { setEvents((await apiRequest<Page<EventItem>>("/events?page=0&size=50")).items ?? []); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }
  async function loadManagedEvents() {
    if (previewMode || !canManage) return;
    setLoading(true); setError("");
    try {
      const response = await apiRequest<Page<EventItem>>("/staff/events?page=0&size=50");
      setManagedEvents(response.items ?? []);
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }
  async function loadInbox() {
    if (previewMode) return;
    setLoading(true); setError(""); setInboxLoadFailed(false);
    try { setInbox(await apiRequest<Inbox>("/me/notifications?page=0&size=50")); }
    catch (reason) { setInboxLoadFailed(true); setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }
  async function loadStaffOptions() {
    if (previewMode || !isAdmin) return;
    try { setStaffOptions(await apiRequest<StaffOption[]>("/admin/events/staff-options")); }
    catch (reason) { setError(errorMessage(reason)); }
  }

  useEffect(() => { if (tab === "events") void loadPublicEvents(); }, [tab, previewMode]);
  useEffect(() => { if (tab === "manage") void loadManagedEvents(); }, [tab, previewMode, canManage]);
  useEffect(() => { if (tab === "inbox") void loadInbox(); }, [tab, previewMode]);
  useEffect(() => { if (canManage && !previewMode) void loadManagedEvents(); if (isAdmin && !previewMode) void loadStaffOptions(); }, [previewMode, canManage, isAdmin]);

  function editEvent(event?: EventItem) {
    setSelectedEvent(event ?? null);
    setAssignmentEvent(null);
    setForm(event ? {
      title: event.title, description: event.description ?? "", location: event.location ?? "",
      startsAt: dateInput(event.startsAt), endsAt: dateInput(event.endsAt),
      registrationOpensAt: dateInput(event.registrationOpensAt), registrationClosesAt: dateInput(event.registrationClosesAt),
      capacity: event.capacity == null ? "" : String(event.capacity),
    } : emptyEvent());
    setAssignees([]); setError(""); setNotice("");
  }
  async function manageAssignees(event: EventItem) {
    setAssignmentEvent(event); setError("");
    try {
      const current = await apiRequest<Array<{ id: number }>>(`/admin/events/${event.id}/assignees`);
      setAssignees(current.map((item) => item.id));
    } catch (reason) { setError(errorMessage(reason)); }
  }
  function eventBody() {
    return { title: form.title.trim(), description: form.description.trim() || null, location: form.location.trim() || null,
      startsAt: localTime(form.startsAt), endsAt: localTime(form.endsAt), registrationOpensAt: localTime(form.registrationOpensAt),
      registrationClosesAt: localTime(form.registrationClosesAt), capacity: form.capacity.trim() ? Number(form.capacity) : null };
  }
  async function saveEvent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (previewMode) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await requestCsrf();
      const path = selectedEvent ? `/staff/events/${selectedEvent.id}` : "/staff/events";
      await apiRequest<EventItem>(path, selectedEvent ? "PATCH" : "POST", eventBody());
      setNotice(selectedEvent ? "Máy chủ đã xác nhận cập nhật sự kiện." : "Máy chủ đã xác nhận tạo sự kiện.");
      editEvent(); await loadManagedEvents(); await loadPublicEvents();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }
  async function saveAssignees() {
    if (previewMode || !assignmentEvent) return;
    if (!window.confirm(`Lưu danh sách cán bộ được giao sự kiện “${assignmentEvent.title}”?`)) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await requestCsrf();
      await apiRequest(`/admin/events/${assignmentEvent.id}/assignees`, "PUT", { accountIds: assignees });
      setNotice("Đã lưu danh sách cán bộ được giao sự kiện."); setAssignmentEvent(null); await loadManagedEvents();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }
  async function register(event: EventItem, cancel = false) {
    if (cancel && !window.confirm(`Hủy đăng ký tham dự “${event.title}”?`)) return;
    setBusyId(event.id); setError(""); setNotice("");
    try {
      await requestCsrf();
      if (cancel) await apiRequest<void>(`/events/${event.id}/registrations/me`, "DELETE");
      else await apiRequest<EventItem>(`/events/${event.id}/registrations`, "POST", {});
      setNotice(cancel ? "Đã hủy đăng ký." : "Đã đăng ký tham dự."); await loadPublicEvents();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusyId(null); }
  }
  async function markRead(id: number) {
    setBusyId(id); setError("");
    try { await requestCsrf(); await apiRequest<void>(`/me/notifications/${id}/read`, "PATCH", {}); await loadInbox(); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setBusyId(null); }
  }
  async function sendNotification(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (previewMode) return;
    const channelName = sendForm.channel === "EMAIL" ? "Email" : "thông báo trong ứng dụng";
    if (!window.confirm(`Gửi ${channelName} tới nhóm người nhận đã chọn? Kết quả giao nhận sẽ do máy chủ trả về.`)) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await requestCsrf();
      const response = await apiRequest<SendResult>("/staff/notifications", "POST", {
        title: sendForm.title, body: sendForm.body, audience: sendForm.audience, channel: sendForm.channel,
        ...(sendForm.audience === "EVENT_REGISTRANTS" ? { eventId: Number(sendForm.eventId) } : {}),
      });
      const deliveryMessage = `Kết quả giao nhận: ${deliveryLabel[response.status] ?? response.status}. Người nhận ${response.recipientCount}; thành công ${response.sentCount}; lỗi ${response.failedCount}.`;
      if (response.status === "FAILED" || response.status === "PARTIAL") setError(deliveryMessage);
      else setNotice(deliveryMessage);
      // Hộp thư được tải khi người dùng mở tab; không để lỗi tải lại che kết quả gửi thành công.
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  return <div className="events-page">
    <section className="events-hero"><div><span className="eyebrow">KẾT NỐI CỘNG ĐỒNG</span><h1>Sự kiện & thông báo</h1><p>Theo dõi hoạt động, đăng ký tham dự và nhận thông tin từ nhà trường.</p></div><div className="events-hero-mark">✦</div></section>
    <nav className="events-tabs" aria-label="Chức năng sự kiện">{tabs.map((item) => <button key={item.id} className={tab === item.id ? "selected" : ""} onClick={() => { setTab(item.id); setError(""); setNotice(""); }}>{item.label}</button>)}</nav>
    {notice && <div className="events-alert success" role="status">{notice}</div>}
    {error && <div className="events-alert error" role="alert">{error}</div>}
    {previewMode ? <div className="surface events-empty"><strong>Minh họa thiết kế</strong><p>Chế độ xem trước không tạo sự kiện, gửi thông báo hoặc giả lập dữ liệu máy chủ.</p></div> : <>
      {tab === "events" && <section className="event-list">{loading ? <div className="surface events-empty">Đang tải sự kiện…</div> : events.length ? events.map((event) => <EventCard key={event.id} event={event} busy={busyId === event.id} onRegister={() => void register(event)} onCancel={() => void register(event, true)} />) : <div className="surface events-empty"><strong>Chưa có sự kiện</strong><p>Sự kiện được công bố sẽ xuất hiện tại đây.</p></div>}</section>}
      {tab === "inbox" && <section className="surface inbox-panel"><div className="events-section-head"><div><span className="eyebrow">HỘP THƯ CÁ NHÂN</span><h2>Thông báo của tôi</h2></div><button className="button button-ghost" onClick={() => void loadInbox()} disabled={loading}>Làm mới</button></div>{loading ? <div className="events-empty">Đang tải thông báo…</div> : inbox?.items.length ? <div className="inbox-list">{inbox.items.map((item) => <article className={`inbox-item ${item.readAt ? "is-read" : ""}`} key={item.id}><div className="inbox-mark">{item.readAt ? "✓" : "•"}</div><div className="inbox-content"><div className="inbox-title"><strong>{item.title}</strong><time>{dateLabel(item.createdAt)}</time></div><p>{item.body}</p>{item.relatedEventTitle && <small>Sự kiện: {item.relatedEventTitle}</small>}</div>{!item.readAt && <button className="button button-secondary" onClick={() => void markRead(item.id)} disabled={busyId === item.id}>Đánh dấu đã đọc</button>}</article>)}</div> : inboxLoadFailed ? <div className="events-empty"><strong>Chưa tải được hộp thư</strong><p>Kiểm tra lỗi phía trên rồi chọn “Làm mới” để thử lại.</p></div> : <div className="events-empty"><strong>Hộp thư đang trống</strong><p>Thông báo được gửi đến tài khoản này sẽ hiển thị tại đây.</p></div>}</section>}
      {tab === "manage" && <div className="events-manage-grid"><section className="surface event-form-panel"><div className="events-section-head"><div><span className="eyebrow">{selectedEvent ? "CHỈNH SỬA" : "TẠO MỚI"}</span><h2>{selectedEvent ? "Cập nhật sự kiện" : "Tạo sự kiện"}</h2></div>{selectedEvent && <button className="button button-ghost" onClick={() => editEvent()}>Sự kiện mới</button>}</div><form className="event-form" onSubmit={saveEvent}><label className="events-field wide"><span>Tên sự kiện *</span><input required maxLength={200} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label><label className="events-field wide"><span>Nội dung</span><textarea rows={4} maxLength={20000} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label><label className="events-field"><span>Địa điểm</span><input maxLength={255} value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} /></label><label className="events-field"><span>Sức chứa (để trống nếu không giới hạn)</span><input type="number" min="1" value={form.capacity} onChange={(e) => setForm({ ...form, capacity: e.target.value })} /></label><label className="events-field"><span>Bắt đầu *</span><input required type="datetime-local" value={form.startsAt} onChange={(e) => setForm({ ...form, startsAt: e.target.value })} /></label><label className="events-field"><span>Kết thúc *</span><input required type="datetime-local" value={form.endsAt} onChange={(e) => setForm({ ...form, endsAt: e.target.value })} /></label><label className="events-field"><span>Mở đăng ký *</span><input required type="datetime-local" value={form.registrationOpensAt} onChange={(e) => setForm({ ...form, registrationOpensAt: e.target.value })} /></label><label className="events-field"><span>Đóng đăng ký *</span><input required type="datetime-local" value={form.registrationClosesAt} onChange={(e) => setForm({ ...form, registrationClosesAt: e.target.value })} /></label><div className="event-form-actions"><button className="button button-primary" disabled={busy}>{busy ? "Đang lưu…" : selectedEvent ? "Lưu thay đổi" : "Tạo và công bố"}</button></div></form><p className="events-footnote">Thời điểm đóng đăng ký phải trước hoặc bằng giờ bắt đầu sự kiện. Sự kiện tạo mới được công bố ngay.</p></section>
        <section className="surface managed-event-panel"><div className="events-section-head"><div><span className="eyebrow">TRONG PHẠM VI CỦA BẠN</span><h2>Sự kiện đã tạo/được giao</h2></div><button className="button button-ghost" onClick={() => void loadManagedEvents()} disabled={loading}>Làm mới</button></div>{managedEvents.length ? <div className="managed-event-list">{managedEvents.map((event) => <article className="managed-event" key={event.id}><div><strong>{event.title}</strong><small>{dateLabel(event.startsAt)} · {event.registeredCount}{event.capacity ? `/${event.capacity}` : ""} lượt đăng ký</small></div><div className="managed-actions"><button className="button button-secondary" onClick={() => editEvent(event)}>Sửa</button>{isAdmin && <button className="button button-ghost" onClick={() => void manageAssignees(event)}>Phân công</button>}</div></article>)}</div> : <div className="events-empty"><strong>Chưa có sự kiện trong phạm vi</strong><p>Tạo sự kiện mới hoặc chờ ADMIN phân công.</p></div>}
          {isAdmin && assignmentEvent && <div className="assignment-box"><h3>Giao “{assignmentEvent.title}” cho cán bộ</h3><label className="events-field"><span>Cán bộ (giữ Ctrl để chọn nhiều)</span><select multiple size={Math.min(7, Math.max(3, staffOptions.length))} value={assignees.map(String)} onChange={(e) => setAssignees(Array.from(e.target.selectedOptions, (option) => Number(option.value)))}>{staffOptions.map((staff) => <option key={staff.id} value={staff.id}>{staff.fullName} · {staff.email}</option>)}</select></label><div className="event-form-actions"><button className="button button-primary" onClick={() => void saveAssignees()} disabled={busy}>Lưu phân công</button><button className="button button-ghost" onClick={() => setAssignmentEvent(null)}>Đóng</button></div></div>}
        </section></div>}
      {tab === "compose" && <section className="surface compose-panel"><div className="events-section-head"><div><span className="eyebrow">THÔNG BÁO TỪ NHÀ TRƯỜNG</span><h2>Soạn thông báo</h2></div></div><form className="event-form compose-form" onSubmit={sendNotification}><label className="events-field"><span>Đối tượng nhận *</span><select value={sendForm.audience} onChange={(e) => setSendForm({ ...sendForm, audience: e.target.value })}><option value="ALUMNI">Cựu sinh viên</option><option value="STUDENT">Sinh viên</option><option value="EVENT_REGISTRANTS">Người đã đăng ký sự kiện</option></select></label><label className="events-field"><span>Kênh gửi *</span><select value={sendForm.channel} onChange={(e) => setSendForm({ ...sendForm, channel: e.target.value })}><option value="IN_APP">Thông báo trong ứng dụng</option><option value="EMAIL">Email</option><option value="SMS" disabled>SMS (chưa được bật)</option></select></label>{sendForm.audience === "EVENT_REGISTRANTS" && <label className="events-field wide"><span>Sự kiện *</span><select required value={sendForm.eventId} onChange={(e) => setSendForm({ ...sendForm, eventId: e.target.value })}><option value="">Chọn sự kiện</option>{managedEvents.map((event) => <option key={event.id} value={event.id}>{event.title}</option>)}</select></label>}<label className="events-field wide"><span>Tiêu đề *</span><input required maxLength={200} value={sendForm.title} onChange={(e) => setSendForm({ ...sendForm, title: e.target.value })} /></label><label className="events-field wide"><span>Nội dung *</span><textarea required rows={6} maxLength={1000} value={sendForm.body} onChange={(e) => setSendForm({ ...sendForm, body: e.target.value })} /></label><div className="event-form-actions"><button className="button button-primary" disabled={busy}>{busy ? "Đang gửi…" : "Gửi thông báo"}</button></div></form><p className="events-footnote">Email chỉ được báo thành công khi adapter nhận xác nhận giao thư. Nếu SMTP chưa cấu hình, kết quả sẽ hiển thị số lượt gửi lỗi. SMS là kênh mở rộng.</p></section>}
    </>}
  </div>;
}

function EventCard({ event, busy, onRegister, onCancel }: { event: EventItem; busy: boolean; onRegister: () => void; onCancel: () => void }) {
  const registered = event.myRegistrationStatus === "REGISTERED";
  const cancelled = event.myRegistrationStatus === "CANCELLED";
  return <article className="surface event-card"><div className="event-card-top"><span className="event-day">{new Intl.DateTimeFormat("vi-VN", { day: "2-digit", month: "short" }).format(new Date(event.startsAt))}</span><div className="event-card-title"><span className="eyebrow">{event.location || "Sự kiện cộng đồng"}</span><h2>{event.title}</h2></div>{registered && <span className="event-state">Đã đăng ký</span>}</div>{event.description && <p className="event-description">{event.description}</p>}<div className="event-meta"><span><b>Thời gian</b>{dateLabel(event.startsAt)} – {dateLabel(event.endsAt)}</span><span><b>Đăng ký đến</b>{dateLabel(event.registrationClosesAt)}</span><span><b>Sức chứa</b>{event.capacity == null ? `${event.registeredCount} người · không giới hạn` : `${event.registeredCount}/${event.capacity} chỗ`}</span></div><div className="event-card-foot"><span>{event.registrationOpen ? "Đang mở đăng ký" : "Đã đóng đăng ký"}{cancelled ? " · Đã hủy trước đó" : ""}</span>{registered ? <button className="button button-secondary" onClick={onCancel} disabled={busy || !event.registrationOpen}>Hủy đăng ký</button> : <button className="button button-primary" onClick={onRegister} disabled={busy || !event.registrationOpen || event.full}>{busy ? "Đang xử lý…" : event.full ? "Đã đủ chỗ" : "Đăng ký tham dự"}</button>}</div></article>;
}
