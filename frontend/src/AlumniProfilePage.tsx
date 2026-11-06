import { useEffect, useState, type FormEvent } from "react";
import { apiRequest, errorMessage } from "./api";
import "./AlumniProfilePage.css";

type Profile = {
  id: number;
  studentCode: string;
  fullName: string;
  facultyId: number;
  facultyName: string;
  majorId: number | null;
  majorName: string | null;
  cohortId: number;
  cohortCode: string;
  graduationYear: number;
  personalEmail: string | null;
  phone: string | null;
  employmentStatus: string;
  employerName: string | null;
  jobTitle: string | null;
  directoryVisibility: "PRIVATE" | "VERIFIED_USERS";
  verificationStatus: string;
  verifiedAt: string | null;
  updatedAt: string;
  avatarFileId: number | null;
};

type Catalogs = {
  faculties: Array<{ id: number; code: string; name: string }>;
  majors: Array<{ id: number; facultyId: number; code: string; name: string }>;
  cohorts: Array<{ id: number; code: string; admissionYear: number; expectedGraduationYear: number }>;
};

type ChangeRequest = {
  id: number;
  proposedValues: Record<string, string | number | null>;
  status: "PENDING" | "APPROVED" | "REJECTED";
  reviewNote: string | null;
  submittedAt: string;
  reviewedAt: string | null;
};

export default function AlumniProfilePage({ previewMode = false }: { previewMode?: boolean }) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [catalogs, setCatalogs] = useState<Catalogs | null>(null);
  const [changeRequests, setChangeRequests] = useState<ChangeRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [avatarUrl, setAvatarUrl] = useState("");

  async function load() {
    setLoading(true);
    setError("");
    try {
      const [nextProfile, nextCatalogs, requests] = await Promise.all([
        apiRequest<Profile>("/me/profile"),
        apiRequest<Catalogs>("/public/catalogs"),
        apiRequest<ChangeRequest[]>("/me/profile/change-requests"),
      ]);
      setProfile(nextProfile);
      setCatalogs(nextCatalogs);
      setChangeRequests(requests);
      setAvatarUrl(nextProfile.avatarFileId ? `/api/v1/me/profile/avatar?v=${nextProfile.avatarFileId}` : "");
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { if (!previewMode) void load(); }, [previewMode]);

  async function saveContact(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true); setError(""); setNotice("");
    const values = new FormData(event.currentTarget);
    try {
      const updated = await apiRequest<Profile>("/me/profile", "PATCH", {
        personalEmail: values.get("personalEmail"),
        phone: values.get("phone"),
        directoryVisibility: values.get("directoryVisibility"),
      });
      setProfile(updated);
      setNotice("Thông tin liên hệ và quyền hiển thị đã được cập nhật.");
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  async function submitIdentityChange(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    setBusy(true); setError(""); setNotice("");
    const values = new FormData(form);
    const proposedValues: Record<string, string | number | null> = {};
    for (const field of ["studentCode", "fullName"] as const) {
      const value = String(values.get(field) ?? "").trim();
      if (value) proposedValues[field] = value;
    }
    for (const field of ["facultyId", "majorId", "cohortId", "graduationYear"] as const) {
      const value = String(values.get(field) ?? "");
      if (value !== "") proposedValues[field] = Number(value);
    }
    if (!Object.keys(proposedValues).length) {
      setError("Nhập ít nhất một trường muốn đề nghị thay đổi.");
      setBusy(false);
      return;
    }
    try {
      const result = await apiRequest<{ requestId: number; status: string }>(
        "/me/profile/change-requests", "POST", { proposedValues },
      );
      const acceptedRequest: ChangeRequest = {
        id: result.requestId,
        proposedValues,
        status: "PENDING",
        reviewNote: null,
        submittedAt: new Date().toISOString(),
        reviewedAt: null,
      };
      setChangeRequests((current) => [acceptedRequest, ...current.filter((item) => item.id !== result.requestId)].slice(0, 20));
      setNotice(`Đã gửi yêu cầu thay đổi #${result.requestId}. Yêu cầu đang chờ cán bộ xác minh.`);
      form.reset();
      try {
        setChangeRequests(await apiRequest<ChangeRequest[]>("/me/profile/change-requests"));
      } catch {
        setNotice(`Máy chủ đã nhận yêu cầu #${result.requestId}; lịch sử đang hiển thị xác nhận vừa nhận.`);
      }
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  async function uploadAvatar(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const file = new FormData(form).get("file");
    if (!(file instanceof File) || file.size === 0) { setError("Chọn ảnh JPEG, PNG hoặc WebP trước khi tải lên."); return; }
    if (file.size > 3 * 1024 * 1024) { setError("Ảnh đại diện không được vượt quá 3 MB."); return; }
    setBusy(true); setError(""); setNotice("");
    try {
      const body = new FormData(); body.append("file", file);
      const result = await apiRequest<{ fileId: number }>("/me/profile/avatar", "POST", body);
      setProfile((current) => current ? { ...current, avatarFileId: result.fileId } : current);
      setAvatarUrl(`/api/v1/me/profile/avatar?v=${result.fileId}`);
      form.reset();
      setNotice("Ảnh đại diện đã được chuẩn hóa và lưu trong vùng riêng tư.");
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }


  if (previewMode) return <>
    <div className="page-title-row"><div><span className="eyebrow">CÁ NHÂN</span><h1>Hồ sơ của tôi</h1><p>Thông tin học vụ được xác minh riêng với thông tin liên hệ do bạn tự cập nhật.</p></div><span className="status-pill"><i />XEM TRƯỚC</span></div>
    <div className="management-callout" role="status">Minh họa thiết kế: không gọi API, không hiển thị dữ liệu mẫu và không lưu thay đổi.</div>
    <div className="profile-grid">
      <section className="surface profile-summary">
        <div className="profile-avatar-wrap"><span className="profile-avatar-fallback" aria-hidden="true">?</span></div>
        <h2>Thông tin hồ sơ</h2><p>Dữ liệu chỉ hiện sau khi máy chủ trả về hồ sơ của bạn.</p>
        <label className="field" htmlFor="preview-avatar"><span>Ảnh đại diện (JPEG, PNG hoặc WebP; tối đa 3 MB)</span><input id="preview-avatar" type="file" accept="image/jpeg,image/png,image/webp" disabled /></label>
      </section>
      <section className="surface"><div className="surface-head"><div><h2>Thông tin liên hệ</h2><span className="muted">Thông tin này không công khai mặc định.</span></div></div><div className="field-grid"><Field name="personalEmail" label="Email liên hệ" type="email" disabled /><Field name="phone" label="Số điện thoại" type="tel" disabled /><Field name="directoryVisibility" label="Hiển thị trong danh bạ" as="select" disabled options={[["PRIVATE", "Riêng tư"], ["VERIFIED_USERS", "Chia sẻ với người dùng đã xác minh"]]} wide /></div></section>
      <section className="surface"><div className="surface-head"><div><h2>Đề nghị thay đổi học vụ</h2><span className="muted">Yêu cầu được gửi tới cán bộ có thẩm quyền.</span></div></div><div className="field-grid"><Field name="studentCode" label="Mã sinh viên mới" disabled /><Field name="fullName" label="Họ tên mới" disabled /><Field name="facultyId" label="Khoa mới" disabled /><Field name="majorId" label="Ngành mới" disabled /><Field name="cohortId" label="Khóa mới" disabled /><Field name="graduationYear" label="Năm tốt nghiệp mới" type="number" disabled /></div></section>
    </div>
  </>;

  if (loading) return <ProfileState title="Đang tải hồ sơ" message="Hệ thống đang lấy hồ sơ và danh mục học vụ." />;
  if (!profile) return <ProfileState title="Chưa tải được hồ sơ" message={error || "Tài khoản chưa có hồ sơ cựu sinh viên."} onRetry={() => void load()} />;

  const availableMajors = catalogs?.majors ?? [];
  const pendingRequest = changeRequests.find((request) => request.status === "PENDING");
  const hasPendingRequest = Boolean(pendingRequest);
  return <>
    <div className="page-title-row"><div><span className="eyebrow">CÁ NHÂN</span><h1>Hồ sơ của tôi</h1><p>Thông tin học vụ được xác minh riêng với thông tin liên hệ do bạn tự cập nhật.</p></div><span className="status-pill"><i /> {profile.verificationStatus}</span></div>
    {notice && <div className="inline-success" role="status">{notice}</div>}
    {error && <div className="inline-error" role="alert">{error}</div>}
    <div className="profile-grid">
      <section className="surface profile-summary">
        <div className="profile-avatar-wrap">{avatarUrl ? <img className="profile-avatar-image" src={avatarUrl} alt="Ảnh đại diện" /> : <span className="profile-avatar-fallback">{profile.fullName.slice(0, 1).toUpperCase()}</span>}</div>
        <h2>{profile.fullName}</h2><p>{profile.studentCode}</p>
        <div className="profile-facts"><span>{profile.facultyName}</span><span>{profile.majorName ?? "Chưa khai báo ngành"}</span><span>{profile.cohortCode} · Tốt nghiệp {profile.graduationYear}</span></div>
        <form className="form-stack avatar-form" onSubmit={uploadAvatar}>
          <label className="field" htmlFor="avatar-file"><span>Ảnh đại diện (JPEG, PNG hoặc WebP; tối đa 3 MB)</span><input id="avatar-file" name="file" type="file" accept="image/jpeg,image/png,image/webp" /></label>
          <button className="button button-secondary" disabled={busy}>Tải ảnh lên</button>
        </form>
      </section>
      <section className="surface">
        <div className="surface-head"><div><h2>Thông tin liên hệ</h2><span className="muted">Các trường này không làm thay đổi dữ liệu học vụ đã xác minh.</span></div></div>
        <form className="data-form" onSubmit={saveContact}>
          <div className="field-grid">
            <Field name="personalEmail" label="Email liên hệ" type="email" defaultValue={profile.personalEmail ?? ""} />
            <Field name="phone" label="Số điện thoại" type="tel" defaultValue={profile.phone ?? ""} />
            <Field name="directoryVisibility" label="Hiển thị trong danh bạ" as="select" defaultValue={profile.directoryVisibility} options={[
              ["PRIVATE", "Riêng tư"], ["VERIFIED_USERS", "Chia sẻ với người dùng đã xác minh"],
            ]} wide />
          </div>
          <div className="form-actions"><button className="button button-primary" disabled={busy}>Lưu thông tin<span>→</span></button></div>
        </form>
      </section>
      <section className="surface">
        <div className="surface-head"><div><h2>Đề nghị thay đổi học vụ</h2><span className="muted">Yêu cầu được lưu để cán bộ có thẩm quyền xem xét.</span></div></div>
        {pendingRequest && <div className="management-callout" role="status">Yêu cầu #{pendingRequest.id} đang chờ cán bộ xử lý. Bạn sẽ gửi yêu cầu mới sau khi yêu cầu này được duyệt hoặc từ chối.</div>}
        <form className="data-form" onSubmit={submitIdentityChange}>
          <div className="field-grid">
            <Field name="studentCode" label="Mã sinh viên mới" disabled={hasPendingRequest} />
            <Field name="fullName" label="Họ tên mới" disabled={hasPendingRequest} />
            <Field name="facultyId" label="Khoa mới" as="select" disabled={hasPendingRequest} options={catalogs?.faculties.map((item) => [String(item.id), item.name]) ?? []} />
            <Field name="majorId" label="Ngành mới" as="select" disabled={hasPendingRequest} options={availableMajors.map((item) => [String(item.id), item.name])} />
            <Field name="cohortId" label="Khóa mới" as="select" disabled={hasPendingRequest} options={catalogs?.cohorts.map((item) => [String(item.id), item.code]) ?? []} />
            <Field name="graduationYear" label="Năm tốt nghiệp mới" type="number" min={1900} max={2200} disabled={hasPendingRequest} />
          </div>
          <div className="form-actions"><button className="button button-secondary" disabled={busy || hasPendingRequest}>{hasPendingRequest ? "Đang chờ xác minh" : "Gửi yêu cầu xem xét"}</button></div>
        </form>
        <div className="change-request-history">
          <h3>Lịch sử yêu cầu</h3>
          {changeRequests.length === 0 ? <p className="muted small">Chưa có yêu cầu thay đổi học vụ.</p> : changeRequests.map((request) => <article className="change-request-item" key={request.id}>
            <div><strong>Yêu cầu #{request.id}</strong><span>{request.status === "PENDING" ? "Chờ xác minh" : request.status === "APPROVED" ? "Đã chấp thuận" : "Từ chối"}</span></div>
            <small>{new Date(request.submittedAt).toLocaleString("vi-VN")}</small>
            {request.reviewNote && <p>{request.reviewNote}</p>}
          </article>)}
        </div>
      </section>
    </div>
  </>;
}

function Field({ name, label, type = "text", required = false, defaultValue, as = "input", options = [], wide = false, min, max, disabled = false }: {
  name: string; label: string; type?: string; required?: boolean; defaultValue?: string | number;
  as?: "input" | "select"; options?: string[][]; wide?: boolean; min?: number; max?: number; disabled?: boolean;
}) {
  return <label className={`field ${wide ? "field-wide" : ""}`} htmlFor={`profile-${name}`}>
    <span>{label}{required && <b> *</b>}</span>
    {as === "select" ? <select id={`profile-${name}`} name={name} defaultValue={defaultValue ?? ""} required={required} disabled={disabled}>
      <option value="">Chọn {label.toLowerCase()}</option>{options.map(([value, text]) => <option key={value} value={value}>{text}</option>)}
    </select> : <input id={`profile-${name}`} name={name} type={type} defaultValue={defaultValue} required={required} min={min} max={max} disabled={disabled} />}
  </label>;
}

function ProfileState({ title, message, onRetry }: { title: string; message: string; onRetry?: () => void }) {
  return <section className="surface empty-state"><strong>{title}</strong><p>{message}</p>{onRetry && <button className="button button-secondary" onClick={onRetry}>Thử tải lại</button>}</section>;
}
