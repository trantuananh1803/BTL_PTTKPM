import { useEffect, useState, type FormEvent } from "react";
import { apiDownload, apiRequest, errorMessage, requestCsrf } from "./api";
import "./JobsPages.css";

type EmploymentType = "FULL_TIME" | "PART_TIME" | "INTERNSHIP";
type Job = {
  id: number;
  title: string;
  department: string | null;
  location: string | null;
  employmentType: EmploymentType | null;
  description: string;
  requirements: string | null;
  applicationDeadline: string | null;
  status: string;
  organizationName: string;
  createdAt: string;
  updatedAt: string;
  applicationCount: number;
  applied: boolean;
  applicationId: number | null;
};
type JobApplication = {
  id: number;
  jobId: number;
  jobTitle: string;
  organizationName?: string;
  applicantName?: string;
  status: string;
  submittedAt: string;
  cvAvailable: boolean;
};
type JobPageResult = { items: Job[]; page: number; size: number; totalElements: number };
type JobForm = {
  title: string;
  department: string;
  location: string;
  employmentType: EmploymentType | "";
  description: string;
  requirements: string;
  applicationDeadline: string;
};

const emptyJob = (): JobForm => ({
  title: "", department: "", location: "", employmentType: "",
  description: "", requirements: "", applicationDeadline: "",
});

const typeLabels: Record<EmploymentType, string> = {
  FULL_TIME: "Toàn thời gian",
  PART_TIME: "Bán thời gian",
  INTERNSHIP: "Thực tập",
};

const statusLabels: Record<string, string> = {
  DRAFT: "Bản nháp", PENDING_REVIEW: "Chờ duyệt", PUBLISHED: "Đang công khai",
  REJECTED: "Bị từ chối", NEEDS_CORRECTION: "Cần bổ sung", CLOSED: "Đã đóng",
  SUBMITTED: "Đã nộp",
};

export function JobsPage({ previewMode }: { previewMode: boolean }) {
  const [tab, setTab] = useState<"search" | "applications">("search");
  const [filters, setFilters] = useState({ keyword: "", location: "", employmentType: "" as EmploymentType | "" });
  const [jobs, setJobs] = useState<Job[]>([]);
  const [applications, setApplications] = useState<JobApplication[]>([]);
  const [files, setFiles] = useState<Record<number, File | undefined>>({});
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function loadJobs(nextFilters = filters) {
    if (previewMode) return;
    setLoading(true);
    setError("");
    try {
      const params = new URLSearchParams();
      if (nextFilters.keyword.trim()) params.set("keyword", nextFilters.keyword.trim());
      if (nextFilters.location.trim()) params.set("location", nextFilters.location.trim());
      if (nextFilters.employmentType) params.set("employmentType", nextFilters.employmentType);
      const result = await apiRequest<JobPageResult>(`/jobs${params.size ? `?${params.toString()}` : ""}`);
      setJobs(result.items ?? []);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setLoading(false);
    }
  }

  async function loadApplications() {
    if (previewMode) return;
    setLoading(true);
    setError("");
    try { setApplications(await apiRequest<JobApplication[]>("/me/job-applications")); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }

  useEffect(() => { if (!previewMode && tab === "search") void loadJobs(); }, [previewMode, tab]);
  useEffect(() => { if (!previewMode && tab === "applications") void loadApplications(); }, [previewMode, tab]);

  async function apply(job: Job) {
    const file = files[job.id];
    if (!file) { setError("Chọn CV dạng PDF trước khi ứng tuyển."); return; }
    if (!file.name.toLowerCase().endsWith(".pdf") || (file.type && file.type !== "application/pdf")) { setError("CV phải là tệp PDF hợp lệ."); return; }
    if (file.size > 10 * 1024 * 1024) { setError("CV không được vượt quá 10 MB."); return; }
    setBusyId(job.id); setError(""); setNotice("");
    try {
      await requestCsrf();
      const form = new FormData();
      form.set("jobId", String(job.id));
      form.set("cv", file);
      await apiRequest<JobApplication>("/jobs/applications", "POST", form);
      setNotice("Đã gửi hồ sơ ứng tuyển. Tin này chỉ nhận một đơn cho mỗi tài khoản.");
      setFiles((current) => ({ ...current, [job.id]: undefined }));
      await loadJobs();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusyId(null); }
  }

  async function downloadOwnCv(applicationId: number) {
    setBusyId(applicationId); setError("");
    try { await download(`/me/job-applications/${applicationId}/cv`); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setBusyId(null); }
  }

  return <div className="jobs-page">
    <div className="page-title-row"><div><span className="eyebrow">KẾT NỐI NGHỀ NGHIỆP</span><h1>Cơ hội nghề nghiệp</h1><p>Tìm tin đã được kiểm duyệt và quản lý hồ sơ ứng tuyển của bạn.</p></div></div>
    <div className="jobs-tabs" role="tablist" aria-label="Việc làm">
      <button role="tab" aria-selected={tab === "search"} className={tab === "search" ? "selected" : ""} onClick={() => { setTab("search"); setError(""); }}>Tìm việc</button>
      <button role="tab" aria-selected={tab === "applications"} className={tab === "applications" ? "selected" : ""} onClick={() => { setTab("applications"); setError(""); }}>Đơn đã nộp</button>
    </div>
    {notice && <div className="jobs-alert success" role="status">{notice}</div>}
    {error && <div className="jobs-alert error" role="alert">{error}</div>}
    {tab === "search" ? <>
      <form className="jobs-filter surface" onSubmit={(event: FormEvent) => { event.preventDefault(); void loadJobs(); }}>
        <label className="field"><span>Từ khóa</span><input value={filters.keyword} onChange={(event) => setFilters({ ...filters, keyword: event.target.value })} placeholder="Vị trí, phòng ban hoặc doanh nghiệp" /></label>
        <label className="field"><span>Địa điểm</span><input value={filters.location} onChange={(event) => setFilters({ ...filters, location: event.target.value })} placeholder="Ví dụ: Hà Nội" /></label>
        <label className="field"><span>Hình thức</span><select value={filters.employmentType} onChange={(event) => setFilters({ ...filters, employmentType: event.target.value as EmploymentType | "" })}><option value="">Tất cả hình thức</option>{Object.entries(typeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <button className="button button-primary" type="submit" disabled={loading || previewMode}>{loading ? "Đang tìm…" : "Tìm cơ hội"}<span>→</span></button>
      </form>
      <section className="jobs-list" aria-live="polite">
        {previewMode ? <EmptyJobs title="Giao diện xem trước" text="Kết quả việc làm chỉ hiển thị khi kết nối API; chế độ xem trước không tạo dữ liệu mẫu." /> : loading ? <EmptyJobs title="Đang tải tin tuyển dụng" text="Vui lòng đợi trong giây lát." /> : jobs.length === 0 ? <EmptyJobs title="Chưa có tin phù hợp" text="Thử một từ khóa hoặc bộ lọc khác." /> : jobs.map((job) => <article className="job-card surface" key={job.id}>
          <div className="job-card-head"><div className="job-company-mark">{job.organizationName.slice(0, 1).toUpperCase()}</div><div className="job-title-block"><span className="eyebrow">{job.organizationName}</span><h2>{job.title}</h2><p>{[job.department, job.location, job.employmentType ? typeLabels[job.employmentType] : null].filter(Boolean).join(" · ")}</p></div><span className="job-status">Đã duyệt</span></div>
          <p className="job-description">{job.description}</p>
          {job.requirements && <details className="job-requirements"><summary>Yêu cầu ứng viên</summary><p>{job.requirements}</p></details>}
          <div className="job-card-foot"><div><span>{job.applicationDeadline ? `Nhận hồ sơ đến ${formatDate(job.applicationDeadline)}` : "Không giới hạn hạn nhận hồ sơ"}</span><small>Tin đã qua kiểm duyệt</small></div>
            {job.applied ? <span className="job-applied">✓ Đã ứng tuyển</span> : <form className="job-apply" onSubmit={(event) => { event.preventDefault(); void apply(job); }}>
              <label className="cv-picker"><input type="file" accept="application/pdf,.pdf" onChange={(event) => setFiles({ ...files, [job.id]: event.target.files?.[0] })} aria-label={`Chọn CV cho ${job.title}`} /><span>{files[job.id]?.name ?? "Chọn CV PDF · tối đa 10 MB"}</span></label>
              <button className="button button-primary" disabled={busyId === job.id || previewMode}>{busyId === job.id ? "Đang gửi…" : "Ứng tuyển"}<span>→</span></button>
            </form>}
          </div>
        </article>)}
      </section>
    </> : <section className="surface application-panel">
      <div className="surface-head"><div><h2>Hồ sơ đã gửi</h2><span className="muted">CV chỉ tải được bởi bạn và doanh nghiệp nhận đơn.</span></div><button className="button button-ghost" disabled={loading || previewMode} onClick={() => void loadApplications()}>↻ Làm mới</button></div>
      {previewMode ? <EmptyJobs title="Giao diện xem trước" text="Đăng nhập bằng tài khoản đủ điều kiện để tải danh sách đơn thật." /> : loading ? <EmptyJobs title="Đang tải đơn ứng tuyển" text="Vui lòng đợi trong giây lát." /> : applications.length === 0 ? <EmptyJobs title="Bạn chưa gửi hồ sơ nào" text="Các đơn đã nộp sẽ xuất hiện tại đây." /> : <div className="application-list">{applications.map((item) => <div className="application-row" key={item.id}><div><strong>{item.jobTitle}</strong><span>{item.organizationName} · {formatDateTime(item.submittedAt)}</span></div><span className="status-pill">{statusLabels[item.status] ?? item.status}</span><button className="button button-secondary" disabled={!item.cvAvailable || busyId === item.id} onClick={() => void downloadOwnCv(item.id)}>{busyId === item.id ? "Đang tải…" : item.cvAvailable ? "Tải CV của tôi" : "CV đã hết hạn"}</button></div>)}</div>}
    </section>}
    <p className="jobs-privacy-note">CV được lưu riêng tư, không có đường dẫn công khai. Mục tiêu lưu trữ là xóa CV và metadata sau 12 tháng kể từ khi tin đóng, theo phê duyệt của nhà trường.</p>
  </div>;
}

export function EmployerJobsPage({ previewMode }: { previewMode: boolean }) {
  const [tab, setTab] = useState<"jobs" | "applications">("jobs");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [applications, setApplications] = useState<JobApplication[]>([]);
  const [form, setForm] = useState<JobForm>(emptyJob());
  const [editingId, setEditingId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function loadJobs() {
    if (previewMode) return;
    setLoading(true); setError("");
    try { setJobs(await apiRequest<Job[]>("/employer/jobs")); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }

  async function loadApplications() {
    if (previewMode) return;
    setLoading(true); setError("");
    try { setApplications(await apiRequest<JobApplication[]>("/employer/applications")); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }

  useEffect(() => { if (!previewMode && tab === "jobs") void loadJobs(); }, [previewMode, tab]);
  useEffect(() => { if (!previewMode && tab === "applications") void loadApplications(); }, [previewMode, tab]);

  function edit(job: Job) {
    setEditingId(job.id);
    setForm({ title: job.title, department: job.department ?? "", location: job.location ?? "",
      employmentType: job.employmentType ?? "", description: job.description,
      requirements: job.requirements ?? "", applicationDeadline: job.applicationDeadline ?? "" });
    setTab("jobs");
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    setSaving(true); setError(""); setNotice("");
    const body = { ...form, employmentType: form.employmentType || null, applicationDeadline: form.applicationDeadline || null };
    try {
      await requestCsrf();
      if (editingId === null) {
        await apiRequest<Job>("/employer/jobs", "POST", body);
        setNotice("Tin đã được gửi vào hàng chờ kiểm duyệt.");
      } else {
        await apiRequest<Job>(`/employer/jobs/${editingId}`, "PATCH", body);
        setNotice("Tin đã được cập nhật và gửi lại để kiểm duyệt trước khi công khai.");
      }
      setForm(emptyJob()); setEditingId(null); await loadJobs();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setSaving(false); }
  }

  async function closeJob(job: Job) {
    if (!window.confirm(`Đóng tin “${job.title}”? Tin sẽ ngừng nhận hồ sơ mới.`)) return;
    setBusyId(job.id); setError(""); setNotice("");
    try {
      await requestCsrf();
      await apiRequest<Job>(`/employer/jobs/${job.id}/close`, "POST", {});
      setNotice("Tin đã được đóng. Thời hạn lưu CV mục tiêu được tính từ thời điểm này.");
      await loadJobs();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusyId(null); }
  }

  async function downloadApplicantCv(applicationId: number) {
    setBusyId(applicationId); setError("");
    try { await download(`/employer/applications/${applicationId}/cv`); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setBusyId(null); }
  }

  return <div className="jobs-page employer-page">
    <div className="page-title-row"><div><span className="eyebrow">NHÀ TUYỂN DỤNG</span><h1>Trung tâm tuyển dụng</h1><p>Quản lý tin của doanh nghiệp đã được duyệt và xem đơn gửi đến tin của mình.</p></div></div>
    {notice && <div className="jobs-alert success" role="status">{notice}</div>}
    {error && <div className="jobs-alert error" role="alert">{error}</div>}
    <div className="jobs-tabs" role="tablist" aria-label="Trung tâm tuyển dụng">
      <button role="tab" aria-selected={tab === "jobs"} className={tab === "jobs" ? "selected" : ""} onClick={() => { setTab("jobs"); setError(""); }}>Tin tuyển dụng</button>
      <button role="tab" aria-selected={tab === "applications"} className={tab === "applications" ? "selected" : ""} onClick={() => { setTab("applications"); setError(""); }}>Hồ sơ ứng tuyển</button>
    </div>
    {tab === "jobs" ? <>
      <form className="surface employer-job-form" onSubmit={save}>
        <div className="surface-head"><div><h2>{editingId === null ? "Tạo tin tuyển dụng" : `Cập nhật tin #${editingId}`}</h2><span className="muted">Tin mới và nội dung cập nhật đều cần kiểm duyệt trước khi công khai.</span></div></div>
        <div className="employer-form-grid">
          <label className="field"><span>Tên vị trí <b>*</b></span><input required maxLength={200} value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} /></label>
          <label className="field"><span>Phòng ban</span><input maxLength={180} value={form.department} onChange={(event) => setForm({ ...form, department: event.target.value })} /></label>
          <label className="field"><span>Địa điểm làm việc</span><input maxLength={180} value={form.location} onChange={(event) => setForm({ ...form, location: event.target.value })} /></label>
          <label className="field"><span>Hình thức</span><select value={form.employmentType} onChange={(event) => setForm({ ...form, employmentType: event.target.value as EmploymentType | "" })}><option value="">Chọn hình thức</option>{Object.entries(typeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
          <label className="field field-wide"><span>Mô tả công việc <b>*</b></span><textarea required maxLength={12000} rows={5} value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} /></label>
          <label className="field field-wide"><span>Yêu cầu ứng viên</span><textarea maxLength={12000} rows={4} value={form.requirements} onChange={(event) => setForm({ ...form, requirements: event.target.value })} /></label>
          <label className="field"><span>Hạn nhận hồ sơ</span><input type="date" value={form.applicationDeadline} onChange={(event) => setForm({ ...form, applicationDeadline: event.target.value })} /></label>
        </div>
        <div className="jobs-form-actions"><button className="button button-primary" disabled={saving || previewMode}>{saving ? "Đang gửi…" : editingId === null ? "Gửi kiểm duyệt" : "Lưu và gửi kiểm duyệt"}<span>→</span></button>{editingId !== null && <button type="button" className="button button-ghost" onClick={() => { setEditingId(null); setForm(emptyJob()); }}>Hủy chỉnh sửa</button>}</div>
      </form>
      <section className="surface application-panel">
        <div className="surface-head"><div><h2>Tin của doanh nghiệp</h2><span className="muted">Chỉ hiển thị tin thuộc tài khoản doanh nghiệp đang đăng nhập.</span></div><button className="button button-ghost" disabled={loading || previewMode} onClick={() => void loadJobs()}>↻ Làm mới</button></div>
        {previewMode ? <EmptyJobs title="Giao diện xem trước" text="Đăng nhập bằng doanh nghiệp đã được duyệt để tải tin tuyển dụng thật." /> : loading ? <EmptyJobs title="Đang tải tin" text="Vui lòng đợi trong giây lát." /> : jobs.length === 0 ? <EmptyJobs title="Chưa có tin tuyển dụng" text="Tin đầu tiên sẽ ở trạng thái chờ duyệt." /> : <div className="employer-job-list">{jobs.map((job) => <article className="employer-job-row" key={job.id}><div className="employer-job-main"><div><strong>{job.title}</strong><span>{[job.department, job.location, job.employmentType ? typeLabels[job.employmentType] : null].filter(Boolean).join(" · ")}</span></div><span className={`status-pill state-${job.status.toLowerCase()}`}>{statusLabels[job.status] ?? job.status}</span></div><p>{job.description}</p><div className="employer-job-actions"><small>{job.applicationCount} hồ sơ ứng tuyển {job.applicationDeadline ? `· Hạn ${formatDate(job.applicationDeadline)}` : "· Không đặt hạn"}</small><div><button className="button button-secondary" disabled={job.status === "CLOSED" || previewMode} onClick={() => edit(job)}>Chỉnh sửa</button>{job.status === "PUBLISHED" && <button className="button button-ghost" disabled={busyId === job.id} onClick={() => void closeJob(job)}>{busyId === job.id ? "Đang đóng…" : "Đóng tin"}</button>}</div></div></article>)}</div>}
      </section>
    </> : <section className="surface application-panel">
      <div className="surface-head"><div><h2>Hồ sơ ứng tuyển</h2><span className="muted">Chỉ đơn gửi tới tin của doanh nghiệp này được hiển thị.</span></div><button className="button button-ghost" disabled={loading || previewMode} onClick={() => void loadApplications()}>↻ Làm mới</button></div>
      {previewMode ? <EmptyJobs title="Giao diện xem trước" text="Dữ liệu ứng viên không được giả lập trong chế độ xem." /> : loading ? <EmptyJobs title="Đang tải hồ sơ" text="Vui lòng đợi trong giây lát." /> : applications.length === 0 ? <EmptyJobs title="Chưa có hồ sơ ứng tuyển" text="Đơn mới sẽ xuất hiện tại đây." /> : <div className="application-list">{applications.map((item) => <div className="application-row" key={item.id}><div><strong>{item.applicantName ?? "Ứng viên"}</strong><span>{item.jobTitle} · {formatDateTime(item.submittedAt)}</span></div><span className="status-pill">{statusLabels[item.status] ?? item.status}</span><button className="button button-secondary" disabled={!item.cvAvailable || busyId === item.id || previewMode} onClick={() => void downloadApplicantCv(item.id)}>{busyId === item.id ? "Đang tải…" : item.cvAvailable ? "Tải CV riêng tư" : "CV đã hết hạn"}</button></div>)}</div>}
    </section>}
    <p className="jobs-privacy-note">CV chỉ tải được qua phiên đã xác thực và kiểm tra quyền ở máy chủ; không có URL công khai.</p>
  </div>;
}

async function download(path: string) {
  const { blob, filename } = await apiDownload(path, "application/pdf");
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
  } finally { window.setTimeout(() => URL.revokeObjectURL(url), 1000); }
}

function EmptyJobs({ title, text }: { title: string; text: string }) {
  return <div className="jobs-empty"><span>⌕</span><strong>{title}</strong><p>{text}</p></div>;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium" }).format(new Date(`${value}T00:00:00`));
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
