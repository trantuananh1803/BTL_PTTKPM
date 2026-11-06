import { useEffect, useMemo, useState, type FormEvent } from "react";
import { apiRequest, errorMessage, requestCsrf } from "./api";
import "./AlumniWorkPages.css";

type EmploymentStatus = "UNKNOWN" | "EMPLOYED" | "SEEKING" | "STUDYING" | "OTHER";
type Employment = {
  employmentStatus: EmploymentStatus;
  employerName: string | null;
  jobTitle: string | null;
  industry: string | null;
  updatedAt: string | null;
};
type CatalogOption = { id: number; code: string; name: string; facultyId: number | null };
type CohortOption = { id: number; code: string; admissionYear: number; expectedGraduationYear: number };
type CatalogOptions = { faculties: CatalogOption[]; majors: CatalogOption[]; cohorts: CohortOption[] };
type ProfileSummary = {
  id: number;
  studentCode: string;
  fullName: string;
  facultyId: number;
  facultyName: string;
  majorId: number | null;
  majorName: string | null;
  cohortId: number;
  cohortCode: string;
  graduationYear: number | null;
  employmentStatus: EmploymentStatus;
  verificationStatus: string;
  archivedAt: string | null;
};
type ProfilePageData = { items: ProfileSummary[]; page: number; size: number; totalElements: number };
type ProfileDetail = ProfileSummary & {
  personalEmail: string | null;
  phone: string | null;
  employerName: string | null;
  jobTitle: string | null;
  industry: string | null;
  directoryVisibility: "PRIVATE" | "VERIFIED_USERS";
  updatedAt: string;
};
type ProfileForm = {
  studentCode: string;
  fullName: string;
  facultyId: string;
  majorId: string;
  cohortId: string;
  graduationYear: string;
  personalEmail: string;
  phone: string;
  employmentStatus: EmploymentStatus;
  employerName: string;
  jobTitle: string;
  industry: string;
  directoryVisibility: "PRIVATE" | "VERIFIED_USERS";
};

const statuses: Array<{ value: EmploymentStatus; label: string }> = [
  { value: "UNKNOWN", label: "Chưa cập nhật" },
  { value: "EMPLOYED", label: "Đang làm việc" },
  { value: "SEEKING", label: "Đang tìm việc" },
  { value: "STUDYING", label: "Đang học tiếp" },
  { value: "OTHER", label: "Khác" },
];

const statusLabel = (status: string) => statuses.find((item) => item.value === status)?.label ?? status;
const verificationLabel = (status: string) => ({ PENDING: "Chờ xác minh", VERIFIED: "Đã xác minh", NEEDS_CORRECTION: "Cần bổ sung" }[status] ?? status);
const emptyForm = (): ProfileForm => ({
  studentCode: "", fullName: "", facultyId: "", majorId: "", cohortId: "", graduationYear: "",
  personalEmail: "", phone: "", employmentStatus: "UNKNOWN", employerName: "", jobTitle: "", industry: "",
  directoryVisibility: "PRIVATE",
});

export function EmploymentSurveyPage({ previewMode }: { previewMode: boolean }) {
  const [data, setData] = useState<Employment | null>(null);
  const [form, setForm] = useState({ employmentStatus: "UNKNOWN" as EmploymentStatus, employerName: "", jobTitle: "", industry: "" });
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    if (previewMode) return;
    setLoading(true);
    apiRequest<Employment>("/me/employment").then((profile) => {
      setData(profile);
      setForm({ employmentStatus: profile.employmentStatus, employerName: profile.employerName ?? "", jobTitle: profile.jobTitle ?? "", industry: profile.industry ?? "" });
      setError("");
    }).catch((reason) => setError(errorMessage(reason))).finally(() => setLoading(false));
  }, [previewMode]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (previewMode || !data) return;
    setSaving(true);
    setError("");
    setNotice("");
    try {
      await requestCsrf();
      const updated = await apiRequest<Employment>("/me/employment", "PATCH", form);
      setData(updated);
      setForm({ employmentStatus: updated.employmentStatus, employerName: updated.employerName ?? "", jobTitle: updated.jobTitle ?? "", industry: updated.industry ?? "" });
      setNotice("Đã lưu thông tin việc làm tự khai báo.");
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setSaving(false);
    }
  }

  return <>
    <div className="page-title-row"><div><span className="eyebrow">CÁ NHÂN · UC2.1</span><h1>Khảo sát việc làm</h1><p>Cập nhật thông tin nghề nghiệp để phục vụ thống kê cựu sinh viên.</p></div><span className="status-pill"><i />{previewMode ? "XEM TRƯỚC" : "DỮ LIỆU CÁ NHÂN"}</span></div>
    {previewMode && <div className="work-preview-note">Chế độ xem trước không gọi API và không hiển thị dữ liệu mẫu.</div>}
    <div className="work-layout">
      <section className="surface work-card">
        <div className="surface-head"><div><h2>Thông tin nghề nghiệp</h2><span className="muted">Các mục này do bạn tự khai báo.</span></div><span className="surface-mark">↗</span></div>
        <form className="work-form" onSubmit={submit}>
          <label className="field"><span>Tình trạng việc làm <b>*</b></span><select required disabled={previewMode} value={form.employmentStatus} onChange={(event) => setForm({ ...form, employmentStatus: event.target.value as EmploymentStatus })}>
            {statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select></label>
          <label className="field"><span>Đơn vị công tác</span><input disabled={previewMode} maxLength={180} value={form.employerName} onChange={(event) => setForm({ ...form, employerName: event.target.value })} /></label>
          <label className="field"><span>Chức danh</span><input disabled={previewMode} maxLength={180} value={form.jobTitle} onChange={(event) => setForm({ ...form, jobTitle: event.target.value })} /></label>
          <label className="field"><span>Lĩnh vực</span><input disabled={previewMode} maxLength={180} value={form.industry} onChange={(event) => setForm({ ...form, industry: event.target.value })} /></label>
          {!previewMode && <div className="work-actions"><button className="button button-primary" disabled={saving || loading || !data}>{saving ? "Đang lưu…" : "Lưu cập nhật"}<span>→</span></button></div>}
        </form>
        {error && <p className="inline-error" role="alert">{error}</p>}
        {notice && <p className="inline-success" role="status">{notice}</p>}
        {!previewMode && !error && !data && <p className="work-muted">{loading ? "Đang tải hồ sơ…" : "Hồ sơ việc làm chưa sẵn sàng."}</p>}
      </section>
      <aside className="surface work-aside"><span className="eyebrow">QUYỀN RIÊNG TƯ</span><h2>Dữ liệu tự khai báo</h2><p>Thông tin việc làm được dùng cho số liệu tổng hợp. Báo cáo cần ghi rõ nguồn tự khai; cán bộ quản lý xem dữ liệu theo phạm vi được giao.</p><div className="work-aside-rule"/><span className="work-updated">Cập nhật gần nhất</span><strong>{previewMode ? "Chưa tải dữ liệu" : data?.updatedAt ? new Date(data.updatedAt).toLocaleString("vi-VN") : "Chưa có thông tin"}</strong></aside>
    </div>
  </>;
}

export function AlumniManagementPage({ previewMode, isAdmin }: { previewMode: boolean; isAdmin: boolean }) {
  const [catalogs, setCatalogs] = useState<CatalogOptions>({ faculties: [], majors: [], cohorts: [] });
  const [items, setItems] = useState<ProfileSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [filters, setFilters] = useState({ q: "", facultyId: "", majorId: "", cohortId: "", graduationYear: "", employmentStatus: "", includeArchived: false });
  const [form, setForm] = useState<ProfileForm>(emptyForm);
  const [selected, setSelected] = useState<ProfileDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const availableMajors = useMemo(() => catalogs.majors.filter((major) => !form.facultyId || major.facultyId === Number(form.facultyId)), [catalogs.majors, form.facultyId]);
  const filterMajors = useMemo(() => catalogs.majors.filter((major) => !filters.facultyId || major.facultyId === Number(filters.facultyId)), [catalogs.majors, filters.facultyId]);

  async function loadCatalogs() {
    const options = await apiRequest<CatalogOptions>("/staff/alumni/catalog-options");
    setCatalogs(options);
  }

  async function search(targetPage = page) {
    if (previewMode) return;
    setLoading(true);
    setError("");
    const params = new URLSearchParams({ page: String(targetPage), size: String(size) });
    if (filters.q.trim()) params.set("q", filters.q.trim());
    if (filters.facultyId) params.set("facultyId", filters.facultyId);
    if (filters.majorId) params.set("majorId", filters.majorId);
    if (filters.cohortId) params.set("cohortId", filters.cohortId);
    if (filters.graduationYear) params.set("graduationYear", filters.graduationYear);
    if (filters.employmentStatus) params.set("employmentStatus", filters.employmentStatus);
    if (filters.includeArchived) params.set("includeArchived", "true");
    try {
      const result = await apiRequest<ProfilePageData>(`/staff/alumni?${params.toString()}`);
      setItems(result.items);
      setTotal(result.totalElements);
      setPage(result.page);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (previewMode) return;
    setLoading(true);
    Promise.all([loadCatalogs(), search(0)]).catch((reason) => setError(errorMessage(reason))).finally(() => setLoading(false));
  }, [previewMode]);

  async function openProfile(id: number) {
    setLoading(true);
    setError("");
    try {
      const profile = await apiRequest<ProfileDetail>(`/staff/alumni/${id}`);
      setSelected(profile);
      setForm({
        studentCode: profile.studentCode, fullName: profile.fullName, facultyId: String(profile.facultyId),
        majorId: profile.majorId == null ? "" : String(profile.majorId), cohortId: String(profile.cohortId),
        graduationYear: profile.graduationYear == null ? "" : String(profile.graduationYear),
        personalEmail: profile.personalEmail ?? "", phone: profile.phone ?? "", employmentStatus: profile.employmentStatus,
        employerName: profile.employerName ?? "", jobTitle: profile.jobTitle ?? "", industry: profile.industry ?? "",
        directoryVisibility: profile.directoryVisibility,
      });
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setLoading(false);
    }
  }

  function startCreate() {
    setSelected(null);
    setForm(emptyForm());
    setError("");
    setNotice("");
  }

  async function submitProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (previewMode) return;
    setSaving(true);
    setError("");
    setNotice("");
    try {
      await requestCsrf();
      if (selected) {
        await apiRequest<ProfileDetail>(`/staff/alumni/${selected.id}`, "PATCH", {
          personalEmail: form.personalEmail || null, phone: form.phone || null,
          employmentStatus: form.employmentStatus, employerName: form.employerName || null,
          jobTitle: form.jobTitle || null, industry: form.industry || null,
          directoryVisibility: form.directoryVisibility,
        });
        setNotice("Đã cập nhật các trường được phép. Thay đổi định danh/học vụ cần gửi qua quy trình xác minh.");
      } else {
        const created = await apiRequest<ProfileDetail>("/staff/alumni", "POST", {
          studentCode: form.studentCode, fullName: form.fullName, facultyId: Number(form.facultyId),
          majorId: form.majorId ? Number(form.majorId) : null, cohortId: Number(form.cohortId),
          graduationYear: Number(form.graduationYear), personalEmail: form.personalEmail || null,
          phone: form.phone || null, employmentStatus: form.employmentStatus,
          employerName: form.employerName || null, jobTitle: form.jobTitle || null,
          industry: form.industry || null, directoryVisibility: form.directoryVisibility,
        });
        setSelected(created);
        setNotice("Đã tạo hồ sơ ở trạng thái chờ xác minh.");
      }
      await search(0);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setSaving(false);
    }
  }

  async function changeArchiveState(profile: ProfileSummary) {
    if (previewMode) return;
    const restoring = profile.archivedAt !== null;
    const action = restoring ? "restore" : "archive";
    const confirmation = restoring ? "Khôi phục hồ sơ này?" : "Lưu trữ hồ sơ này? Hồ sơ sẽ được giữ lại, không xóa khỏi hệ thống.";
    if (!window.confirm(confirmation)) return;
    setLoading(true);
    setError("");
    try {
      await requestCsrf();
      await apiRequest(`/staff/alumni/${profile.id}/${action}`, "POST");
      if (selected?.id === profile.id) setSelected(null);
      setNotice(restoring ? "Đã khôi phục hồ sơ." : "Đã lưu trữ hồ sơ.");
      await search(page);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setLoading(false);
    }
  }

  async function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await search(0);
  }

  return <>
    <div className="page-title-row"><div><span className="eyebrow">QUẢN LÝ · UC7.1–UC7.2</span><h1>Hồ sơ cựu sinh viên</h1><p>{isAdmin ? "Phạm vi toàn trường." : "Chỉ hiển thị hồ sơ thuộc khoa được phân công."} Mã sinh viên và họ tên dùng cho đối chiếu; danh sách không hiển thị thông tin liên hệ.</p></div><span className="status-pill"><i />{previewMode ? "XEM TRƯỚC" : isAdmin ? "TOÀN TRƯỜNG" : "THEO PHẠM VI KHOA"}</span></div>
    {previewMode && <div className="work-preview-note">Chế độ xem trước không gọi API và không hiển thị dữ liệu mẫu.</div>}
    {notice && <p className="inline-success work-notice" role="status">{notice}</p>}
    {error && <p className="inline-error work-notice" role="alert">{error}</p>}
    <div className="alumni-management-grid">
      <section className="surface work-card alumni-filter-card">
        <div className="surface-head"><div><h2>Tìm kiếm và lọc hồ sơ</h2><span className="muted">Bộ lọc áp dụng ở máy chủ cùng phạm vi vai trò.</span></div></div>
        <form className="work-form" onSubmit={submitSearch}>
          <label className="field alumni-field-wide"><span>Họ tên hoặc mã sinh viên</span><input value={filters.q} onChange={(event) => setFilters({ ...filters, q: event.target.value })} /></label>
          <label className="field"><span>Khoa</span><select value={filters.facultyId} onChange={(event) => setFilters({ ...filters, facultyId: event.target.value, majorId: "" })}><option value="">Tất cả trong phạm vi</option>{catalogs.faculties.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label className="field"><span>Ngành</span><select value={filters.majorId} onChange={(event) => setFilters({ ...filters, majorId: event.target.value })}><option value="">Tất cả ngành</option>{filterMajors.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label className="field"><span>Khóa</span><select value={filters.cohortId} onChange={(event) => setFilters({ ...filters, cohortId: event.target.value })}><option value="">Tất cả khóa</option>{catalogs.cohorts.map((item) => <option key={item.id} value={item.id}>{item.code}</option>)}</select></label>
          <label className="field"><span>Năm tốt nghiệp</span><input type="number" min="1900" max="2200" value={filters.graduationYear} onChange={(event) => setFilters({ ...filters, graduationYear: event.target.value })} /></label>
          <label className="field"><span>Tình trạng việc làm</span><select value={filters.employmentStatus} onChange={(event) => setFilters({ ...filters, employmentStatus: event.target.value })}><option value="">Tất cả tình trạng</option>{statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>
          <label className="work-check"><input type="checkbox" checked={filters.includeArchived} onChange={(event) => setFilters({ ...filters, includeArchived: event.target.checked })} /><span>Hiện cả hồ sơ đã lưu trữ</span></label>
          <div className="work-actions"><button className="button button-primary" disabled={loading || previewMode}>{loading ? "Đang tải…" : "Tìm hồ sơ"}<span>⌕</span></button><button className="button button-secondary" type="button" disabled={previewMode} onClick={() => { setFilters({ q: "", facultyId: "", majorId: "", cohortId: "", graduationYear: "", employmentStatus: "", includeArchived: false }); window.setTimeout(() => void search(0), 0); }}>Xóa bộ lọc</button></div>
        </form>
      </section>
      <section className="surface work-card alumni-editor-card">
        <div className="surface-head"><div><h2>{selected ? `Hồ sơ #${selected.id}` : "Tạo hồ sơ"}</h2><span className="muted">{selected ? "Trường định danh/học vụ chỉ đọc; thay đổi cần xác minh." : "Hồ sơ mới sẽ ở trạng thái chờ xác minh."}</span></div>{selected && <button className="button button-ghost" type="button" onClick={startCreate}>＋ Tạo mới</button>}</div>
        <form className="work-form" onSubmit={submitProfile}>
          <label className="field"><span>Mã sinh viên <b>*</b></span><input required maxLength={32} value={form.studentCode} disabled={Boolean(selected)} onChange={(event) => setForm({ ...form, studentCode: event.target.value })} /></label>
          <label className="field"><span>Họ và tên <b>*</b></span><input required maxLength={180} value={form.fullName} disabled={Boolean(selected)} onChange={(event) => setForm({ ...form, fullName: event.target.value })} /></label>
          <label className="field"><span>Khoa <b>*</b></span><select required value={form.facultyId} disabled={Boolean(selected)} onChange={(event) => setForm({ ...form, facultyId: event.target.value, majorId: "" })}><option value="">Chọn khoa</option>{catalogs.faculties.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label className="field"><span>Ngành</span><select value={form.majorId} disabled={Boolean(selected)} onChange={(event) => setForm({ ...form, majorId: event.target.value })}><option value="">Chưa chọn ngành</option>{availableMajors.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label className="field"><span>Khóa <b>*</b></span><select required value={form.cohortId} disabled={Boolean(selected)} onChange={(event) => setForm({ ...form, cohortId: event.target.value })}><option value="">Chọn khóa</option>{catalogs.cohorts.map((item) => <option key={item.id} value={item.id}>{item.code}</option>)}</select></label>
          <label className="field"><span>Năm tốt nghiệp <b>*</b></span><input type="number" min="1900" max="2200" required value={form.graduationYear} disabled={Boolean(selected)} onChange={(event) => setForm({ ...form, graduationYear: event.target.value })} /></label>
          <label className="field"><span>Email liên hệ</span><input type="email" maxLength={254} value={form.personalEmail} onChange={(event) => setForm({ ...form, personalEmail: event.target.value })} /></label>
          <label className="field"><span>Điện thoại</span><input type="tel" maxLength={32} value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} /></label>
          <label className="field"><span>Tình trạng việc làm</span><select value={form.employmentStatus} onChange={(event) => setForm({ ...form, employmentStatus: event.target.value as EmploymentStatus })}>{statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>
          <label className="field"><span>Đơn vị công tác</span><input maxLength={180} value={form.employerName} onChange={(event) => setForm({ ...form, employerName: event.target.value })} /></label>
          <label className="field"><span>Chức danh</span><input maxLength={180} value={form.jobTitle} onChange={(event) => setForm({ ...form, jobTitle: event.target.value })} /></label>
          <label className="field"><span>Lĩnh vực</span><input maxLength={180} value={form.industry} onChange={(event) => setForm({ ...form, industry: event.target.value })} /></label>
          <label className="field alumni-field-wide"><span>Hiển thị trong thư mục</span><select value={form.directoryVisibility} onChange={(event) => setForm({ ...form, directoryVisibility: event.target.value as ProfileForm["directoryVisibility"] })}><option value="PRIVATE">Riêng tư</option><option value="VERIFIED_USERS">Cựu sinh viên đã xác minh</option></select></label>
          {selected && <div className="alumni-profile-state"><span>{verificationLabel(selected.verificationStatus)}</span><span>Cập nhật: {new Date(selected.updatedAt).toLocaleString("vi-VN")}</span></div>}
          {!previewMode && <div className="work-actions alumni-field-wide"><button className="button button-primary" disabled={saving || loading}>{saving ? "Đang lưu…" : selected ? "Lưu thay đổi" : "Tạo hồ sơ"}<span>→</span></button>{selected && selected.archivedAt === null && <button type="button" className="button button-secondary" disabled={loading} onClick={() => void changeArchiveState(selected)}>Lưu trữ</button>}</div>}
        </form>
      </section>
    </div>
    <section className="surface work-card alumni-results-card">
      <div className="surface-head"><div><h2>Kết quả hồ sơ</h2><span className="muted">{previewMode ? "Kết quả chỉ xuất hiện khi API được kết nối." : `${total.toLocaleString("vi-VN")} hồ sơ phù hợp với quyền và bộ lọc.`}</span></div><button className="button button-ghost" disabled={loading || previewMode} onClick={() => void search(page)}>↻ Làm mới</button></div>
      {previewMode ? <div className="work-empty">Không tải dữ liệu trong chế độ xem trước.</div> : items.length === 0 ? <div className="work-empty">{loading ? "Đang tải hồ sơ…" : "Không tìm thấy hồ sơ phù hợp."}</div> : <div className="work-table-wrap"><table className="alumni-table"><thead><tr><th>Mã SV</th><th>Họ tên</th><th>Khoa / ngành</th><th>Khóa</th><th>Năm TN</th><th>Việc làm</th><th>Xác minh</th><th>Thao tác</th></tr></thead><tbody>{items.map((item) => <tr key={item.id} className={item.archivedAt ? "is-archived" : ""}><td>{item.studentCode}</td><td>{item.fullName}</td><td>{item.facultyName}{item.majorName ? ` / ${item.majorName}` : ""}</td><td>{item.cohortCode}</td><td>{item.graduationYear ?? "—"}</td><td>{statusLabel(item.employmentStatus)}</td><td><span className={`alumni-status status-${item.verificationStatus.toLowerCase()}`}>{verificationLabel(item.verificationStatus)}</span>{item.archivedAt && <small className="archived-label"> · Đã lưu trữ</small>}</td><td><div className="alumni-row-actions"><button type="button" onClick={() => void openProfile(item.id)} disabled={loading}>Mở</button><button type="button" onClick={() => void changeArchiveState(item)} disabled={loading}>{item.archivedAt ? "Khôi phục" : "Lưu trữ"}</button></div></td></tr>)}</tbody></table></div>}
      {!previewMode && total > size && <div className="work-pagination"><span>Trang {page + 1} / {Math.max(1, Math.ceil(total / size))}</span><div><button className="button button-secondary" disabled={page <= 0 || loading} onClick={() => void search(page - 1)}>Trước</button><button className="button button-secondary" disabled={(page + 1) * size >= total || loading} onClick={() => void search(page + 1)}>Sau</button></div></div>}
    </section>
  </>;
}
