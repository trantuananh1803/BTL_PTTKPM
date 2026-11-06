import { useEffect, useMemo, useState, type FormEvent } from "react";
import { apiDownload, apiRequest, errorMessage, requestCsrf } from "./api";
import "./ManagementPages.css";

type Role = "ALUMNI" | "STUDENT" | "EMPLOYER" | "FACULTY_STAFF" | "ADMIN";
type Account = { id: number; email: string; fullName: string; accountStatus: string; emailVerified: boolean; roles: string[]; permissions: string[]; facultyIds: number[] };
type AlumniPromotionCandidate = { profileId: number; studentCode: string; fullName: string; facultyName: string; cohortCode: string };
type Catalog = { type: string; id: number; code: string; name: string; active: boolean; facultyId: number | null; admissionYear: number | null; expectedGraduationYear: number | null };
type Review = { type: string; id: number; status: string; displayName: string; email: string | null; studentCode: string | null; facultyName: string | null; majorName: string | null; cohortCode: string | null; graduationYear: number | null; organizationName: string | null; businessRegistrationNumber: string | null; website: string | null; jobTitle: string | null; jobDescription: string | null; submittedAt: string | null; reviewNote: string | null; proposedValues: Record<string, string | number | null> | null };
type ImportBatch = { id: number; filename: string; status: string; totalRows: number; validRows: number; skippedRows: number; expiresAt: string; previewRows: Array<{ rowNumber: number; studentCode: string; fullName: string; facultyCode: string; majorCode: string; cohortCode: string; graduationYear: number | null }>; errors: Array<{ rowNumber: number; code: string; message: string }> };

const roleOptions: Array<[string, string]> = [["FACULTY_STAFF", "Cán bộ quản lý"], ["ADMIN", "Quản trị viên"], ["ALUMNI", "Cựu sinh viên"], ["STUDENT", "Sinh viên"], ["EMPLOYER", "Nhà tuyển dụng"]];
const permissionOptions: Array<[string, string]> = [["EMPLOYER_REVIEW", "Duyệt doanh nghiệp"], ["JOB_REVIEW", "Duyệt tin tuyển dụng"], ["ALUMNI_IMPORT", "Nhập hồ sơ cựu sinh viên"]];

export function AdminConsolePage({ previewMode, permissions }: { previewMode: boolean; permissions: string[] }) {
  const [tab, setTab] = useState<"accounts" | "catalogs" | "import">("accounts");
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [faculties, setFaculties] = useState<Catalog[]>([]);
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<Account | null>(null);
  const [promotionCandidates, setPromotionCandidates] = useState<AlumniPromotionCandidate[]>([]);
  const [promotionProfileId, setPromotionProfileId] = useState("");
  const [identityMatchConfirmed, setIdentityMatchConfirmed] = useState(false);
  const [roles, setRoles] = useState<string[]>(["FACULTY_STAFF"]);
  const [facultyIds, setFacultyIds] = useState<number[]>([]);
  const [grants, setGrants] = useState<string[]>([]);
  const [accountStatus, setAccountStatus] = useState("PENDING_EMAIL");
  const [email, setEmail] = useState("");
  const [fullName, setFullName] = useState("");
  const [catalogType, setCatalogType] = useState("FACULTY");
  const [catalogs, setCatalogs] = useState<Catalog[]>([]);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [parentFacultyId, setParentFacultyId] = useState("");
  const [admissionYear, setAdmissionYear] = useState("");
  const [expectedGraduationYear, setExpectedGraduationYear] = useState("");
  const [activeFlag, setActiveFlag] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const canImport = permissions.includes("ALUMNI_IMPORT");
  useEffect(() => {
    if (previewMode) return;
    void loadAccounts();
    apiRequest<Catalog[]>("/admin/catalogs?type=FACULTY").then(setFaculties).catch((reason) => setError(errorMessage(reason)));
  }, [previewMode]);
  useEffect(() => { if (!previewMode && tab === "catalogs") void loadCatalogs(catalogType); }, [previewMode, tab, catalogType]);

  async function loadAccounts(search = query) {
    try { setAccounts(await apiRequest<Account[]>(`/admin/accounts${search ? `?q=${encodeURIComponent(search)}` : ""}`)); }
    catch (reason) { setError(errorMessage(reason)); }
  }
  async function loadCatalogs(type: string) {
    try { setCatalogs(await apiRequest<Catalog[]>(`/admin/catalogs?type=${type}`)); }
    catch (reason) { setError(errorMessage(reason)); }
  }
  function chooseAccount(account: Account) {
    setSelected(account); setEmail(account.email); setFullName(account.fullName); setRoles(account.roles);
    setFacultyIds(account.facultyIds); setGrants(account.permissions); setAccountStatus(account.accountStatus); setNotice(""); setError("");
    setPromotionProfileId(""); setPromotionCandidates([]); setIdentityMatchConfirmed(false);
    if (account.roles.includes("STUDENT") && !account.roles.includes("ALUMNI")) {
      apiRequest<AlumniPromotionCandidate[]>("/admin/accounts/alumni-promotion-candidates")
        .then(setPromotionCandidates).catch((reason) => setError(errorMessage(reason)));
    }
  }
  function resetAccount() {
    setSelected(null); setEmail(""); setFullName(""); setRoles(["FACULTY_STAFF"]); setFacultyIds([]); setGrants([]); setAccountStatus("PENDING_EMAIL");
    setPromotionCandidates([]); setPromotionProfileId(""); setIdentityMatchConfirmed(false);
  }
  async function saveAccount(event: FormEvent) {
    event.preventDefault(); if (previewMode) return;
    if (selected && !window.confirm("Lưu các thay đổi vai trò, quyền, phạm vi khoa hoặc trạng thái tài khoản này?")) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await requestCsrf();
      const body = { roles, facultyIds, permissions: grants, ...(selected ? { accountStatus } : { email, fullName }) };
      if (selected) await apiRequest<Account>(`/admin/accounts/${selected.id}`, "PATCH", body);
      else await apiRequest<Account>("/admin/accounts", "POST", body);
      setNotice(selected ? "Đã cập nhật vai trò, quyền và trạng thái tài khoản." : "Đã tạo tài khoản chờ xác minh Email. Hãy gửi mã kích hoạt từ danh sách tài khoản.");
      resetAccount(); await loadAccounts();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }
  async function sendActivation(accountId: number) {
    setBusy(true); setError(""); setNotice("");
    try { await requestCsrf(); const response = await apiRequest<{ message: string }>(`/admin/accounts/${accountId}/activation-otp`, "POST", {}); setNotice(response.message); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }
  async function promoteStudent() {
    if (previewMode || !selected || !promotionProfileId || !identityMatchConfirmed) return;
    if (!window.confirm(`Xác nhận đã đối chiếu danh tính và mã sinh viên với nguồn chính thức? Sau đó chuyển ${selected.fullName} từ STUDENT sang ALUMNI và liên kết hồ sơ đã xác minh. Tài khoản sẽ cần đăng nhập lại.`)) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await requestCsrf();
      const updated = await apiRequest<Account>(`/admin/accounts/${selected.id}/alumni-promotion`, "POST", { alumniProfileId: Number(promotionProfileId), confirmIdentityMatch: true });
      setSelected(updated); setRoles(updated.roles); setPromotionProfileId(""); setIdentityMatchConfirmed(false); setPromotionCandidates((items) => items.filter((item) => item.profileId !== Number(promotionProfileId)));
      setNotice("Đã chuyển tài khoản sang ALUMNI, liên kết hồ sơ đã xác minh và thu hồi phiên đăng nhập cũ.");
      await loadAccounts();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }
  function chooseCatalog(item: Catalog) {
    setCatalog(item); setCode(item.code); setName(item.type === "COHORT" ? "" : item.name);
    setParentFacultyId(item.facultyId ? String(item.facultyId) : ""); setAdmissionYear(item.admissionYear ? String(item.admissionYear) : "");
    setExpectedGraduationYear(item.expectedGraduationYear ? String(item.expectedGraduationYear) : ""); setActiveFlag(item.active);
  }
  function resetCatalog() { setCatalog(null); setCode(""); setName(""); setParentFacultyId(""); setAdmissionYear(""); setExpectedGraduationYear(""); setActiveFlag(true); }
  async function saveCatalog(event: FormEvent) {
    event.preventDefault(); if (previewMode) return;
    setBusy(true); setError(""); setNotice("");
    const body: Record<string, unknown> = { type: catalogType, code, active: activeFlag };
    if (catalogType !== "COHORT") body.name = name;
    if (catalogType === "MAJOR") body.facultyId = Number(parentFacultyId);
    if (catalogType === "COHORT") { body.admissionYear = Number(admissionYear); body.expectedGraduationYear = Number(expectedGraduationYear); }
    try {
      await requestCsrf();
      if (catalog) await apiRequest<Catalog>(`/admin/catalogs/${catalogType}/${catalog.id}`, "PATCH", body);
      else await apiRequest<Catalog>("/admin/catalogs", "POST", body);
      setNotice(catalog ? "Đã cập nhật danh mục." : "Đã tạo danh mục."); resetCatalog(); await loadCatalogs(catalogType);
      setFaculties(await apiRequest<Catalog[]>("/admin/catalogs?type=FACULTY"));
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  return <section className="management-page">
    <div className="page-title-row"><div><span className="eyebrow">QUẢN TRỊ HỆ THỐNG</span><h1>Quản lý nền tảng</h1><p>Tài khoản, phân quyền, danh mục học vụ và nhập dữ liệu theo quyền được cấp.</p></div><span className="status-pill"><i />{previewMode ? "XEM TRƯỚC" : "KẾT NỐI API"}</span></div>
    {previewMode && <div className="management-callout">Chế độ xem giao diện không gọi API và không hiển thị dữ liệu mẫu.</div>}
    <div className="management-tabs" role="tablist" aria-label="Chức năng quản trị">
      <button className={tab === "accounts" ? "selected" : ""} onClick={() => setTab("accounts")}>Tài khoản & quyền</button>
      <button className={tab === "catalogs" ? "selected" : ""} onClick={() => setTab("catalogs")}>Khoa, ngành, khóa</button>
      {canImport && <button className={tab === "import" ? "selected" : ""} onClick={() => setTab("import")}>Nhập Excel</button>}
    </div>
    {error && <p className="inline-error" role="alert">{error}</p>}{notice && <p className="inline-success" role="status">{notice}</p>}
    {tab === "accounts" && <div className="management-grid">
      <section className="surface management-surface"><div className="surface-head"><div><h2>{selected ? `Cập nhật tài khoản #${selected.id}` : "Cấp tài khoản quản trị"}</h2><span className="muted">Tài khoản cán bộ/quản trị chờ OTP Email để kích hoạt.</span></div></div>
        <form className="management-form" onSubmit={saveAccount}>
          {!selected && <><label className="field"><span>Email đăng nhập *</span><input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required maxLength={254} /></label><label className="field"><span>Họ và tên *</span><input value={fullName} onChange={(event) => setFullName(event.target.value)} required maxLength={180} /></label></>}
          <fieldset><legend>Vai trò</legend><div className="choice-grid">{(selected ? roleOptions : roleOptions.slice(0, 2)).filter(([value]) => !(selected?.roles.includes("STUDENT") && !selected.roles.includes("ALUMNI") && value === "ALUMNI")).map(([value, label]) => <label key={value}><input type="checkbox" checked={roles.includes(value)} onChange={() => setRoles(toggle(roles, value))} />{label}</label>)}</div>{selected?.roles.includes("STUDENT") && !selected.roles.includes("ALUMNI") && <small>Chuyển STUDENT sang ALUMNI bằng quy trình xác minh hồ sơ bên dưới.</small>}</fieldset>
          <fieldset><legend>Quyền kiểm duyệt/nhập riêng</legend><div className="choice-grid">{permissionOptions.map(([value, label]) => <label key={value}><input type="checkbox" checked={grants.includes(value)} onChange={() => setGrants(toggle(grants, value))} />{label}</label>)}</div></fieldset>
          {roles.includes("FACULTY_STAFF") && <fieldset><legend>Phạm vi khoa của cán bộ *</legend><div className="choice-grid">{faculties.map((item) => <label key={item.id}><input type="checkbox" checked={facultyIds.includes(item.id)} onChange={() => setFacultyIds(toggleNumber(facultyIds, item.id))} />{item.name}</label>)}</div>{faculties.length === 0 && <small>Chưa có khoa hoạt động. Tạo khoa ở thẻ “Khoa, ngành, khóa”.</small>}</fieldset>}
          {selected && <label className="field"><span>Trạng thái tài khoản</span><select value={accountStatus} onChange={(event) => setAccountStatus(event.target.value)}><option value="PENDING_EMAIL">Chờ xác minh Email</option><option value="ACTIVE">Hoạt động</option><option value="LOCKED">Đã khóa</option><option value="DISABLED">Đã vô hiệu hóa</option></select></label>}
          <div className="management-actions"><button className="button button-primary" disabled={busy}>{busy ? "Đang lưu…" : selected ? "Lưu thay đổi" : "Tạo tài khoản"}</button>{selected && <button type="button" className="button button-secondary" onClick={resetAccount}>Hủy chỉnh sửa</button>}</div>
        </form>
        {selected?.roles.includes("STUDENT") && !selected.roles.includes("ALUMNI") && <section className="graduation-promotion">
          <h3>Chuyển sinh viên thành cựu sinh viên</h3>
          <p>Chỉ chọn hồ sơ đã xác minh, chưa liên kết. Đối chiếu người sở hữu tài khoản và mã sinh viên với nguồn chính thức của trường; email hoặc tên trùng nhau không đủ để xác nhận.</p>
          <label className="field"><span>Hồ sơ cựu sinh viên đã xác minh *</span><select value={promotionProfileId} onChange={(event) => setPromotionProfileId(event.target.value)} disabled={busy} required><option value="">Chọn hồ sơ</option>{promotionCandidates.map((item) => <option key={item.profileId} value={item.profileId}>{item.studentCode} · {item.fullName} · {item.facultyName} · {item.cohortCode}</option>)}</select></label>
          {promotionCandidates.length === 0 && <small>Chưa có hồ sơ cựu sinh viên chưa liên kết và đã xác minh.</small>}
          <label className="identity-match-confirm"><input type="checkbox" checked={identityMatchConfirmed} onChange={(event) => setIdentityMatchConfirmed(event.target.checked)} disabled={busy || !promotionProfileId} /><span>Tôi đã xác minh đây là đúng người và đúng mã sinh viên theo nguồn chính thức.</span></label>
          <button type="button" className="button button-primary" onClick={() => void promoteStudent()} disabled={busy || !promotionProfileId || !identityMatchConfirmed}>{busy ? "Đang xử lý…" : "Xác nhận chuyển vai trò"}</button>
        </section>}
      </section>
      <section className="surface management-surface"><div className="surface-head"><div><h2>Tài khoản đã cấp</h2><span className="muted">Tối đa 200 kết quả mới nhất.</span></div></div>
        <form className="search-row" onSubmit={(event) => { event.preventDefault(); void loadAccounts(query); }}><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Tìm theo email hoặc họ tên"/><button className="button button-secondary">Tìm</button></form>
        <div className="management-list">{accounts.map((account) => <article className="account-row" key={account.id}><div><strong>{account.fullName}</strong><span>{account.email}</span><small>{account.roles.join(" · ")} · {account.accountStatus}{account.emailVerified ? " · Email đã xác minh" : ""}</small><small>Quyền: {account.permissions.join(", ") || "Không có"}</small></div><div className="row-actions"><button className="button button-ghost" onClick={() => chooseAccount(account)}>Quản lý</button>{account.accountStatus === "PENDING_EMAIL" && <button className="button button-ghost" disabled={busy} onClick={() => void sendActivation(account.id)}>Gửi OTP kích hoạt</button>}</div></article>)}{accounts.length === 0 && <div className="empty-state"><strong>Chưa có kết quả tài khoản</strong><p>Danh sách sẽ hiện dữ liệu trả về từ máy chủ sau khi kết nối.</p></div>}</div>
      </section>
    </div>}
    {tab === "catalogs" && <div className="management-grid">
      <section className="surface management-surface"><div className="surface-head"><div><h2>{catalog ? `Cập nhật ${catalog.type} #${catalog.id}` : "Danh mục mới"}</h2><span className="muted">Danh mục được ngừng hoạt động thay vì xóa.</span></div></div>
        <label className="field"><span>Loại danh mục</span><select value={catalogType} onChange={(event) => { setCatalogType(event.target.value); resetCatalog(); }} disabled={Boolean(catalog)}><option value="FACULTY">Khoa</option><option value="MAJOR">Ngành</option><option value="COHORT">Khóa</option></select></label>
        <form className="management-form" onSubmit={saveCatalog}><label className="field"><span>Mã *</span><input value={code} onChange={(event) => setCode(event.target.value)} required maxLength={40}/></label>
          {catalogType !== "COHORT" && <label className="field"><span>Tên *</span><input value={name} onChange={(event) => setName(event.target.value)} required maxLength={180}/></label>}
          {catalogType === "MAJOR" && <label className="field"><span>Khoa *</span><select value={parentFacultyId} onChange={(event) => setParentFacultyId(event.target.value)} required><option value="">Chọn khoa</option>{faculties.map((item) => <option key={item.id} value={item.id}>{item.code} · {item.name}</option>)}</select></label>}
          {catalogType === "COHORT" && <div className="field-grid"><label className="field"><span>Năm nhập học *</span><input type="number" min="1900" max="2200" value={admissionYear} onChange={(event) => setAdmissionYear(event.target.value)} required/></label><label className="field"><span>Năm tốt nghiệp dự kiến *</span><input type="number" min="1900" max="2200" value={expectedGraduationYear} onChange={(event) => setExpectedGraduationYear(event.target.value)} required/></label></div>}
          {catalog && <label className="field"><span>Trạng thái</span><select value={activeFlag ? "true" : "false"} onChange={(event) => setActiveFlag(event.target.value === "true")}><option value="true">Đang hoạt động</option><option value="false">Ngừng hoạt động</option></select></label>}
          <div className="management-actions"><button className="button button-primary" disabled={busy}>{catalog ? "Lưu danh mục" : "Tạo danh mục"}</button>{catalog && <button type="button" className="button button-secondary" onClick={resetCatalog}>Hủy chỉnh sửa</button>}</div>
        </form>
      </section>
      <section className="surface management-surface"><div className="surface-head"><div><h2>Danh sách {catalogType === "FACULTY" ? "khoa" : catalogType === "MAJOR" ? "ngành" : "khóa"}</h2><span className="muted">Dữ liệu hiện tại từ API quản trị.</span></div><button className="button button-ghost" onClick={() => void loadCatalogs(catalogType)}>Tải lại</button></div>
        <div className="management-list">{catalogs.map((item) => <article className="account-row" key={item.id}><div><strong>{item.type === "COHORT" ? item.code : `${item.code} · ${item.name}`}</strong><span>{item.type === "MAJOR" ? `Mã khoa: ${item.facultyId}` : item.type === "COHORT" ? `${item.admissionYear} – ${item.expectedGraduationYear}` : "Danh mục cấp khoa"}</span><small>{item.active ? "Đang hoạt động" : "Ngừng hoạt động"}</small></div><button className="button button-ghost" onClick={() => chooseCatalog(item)}>Chỉnh sửa</button></article>)}{catalogs.length === 0 && <div className="empty-state"><strong>Chưa có danh mục</strong><p>Tạo mới một khoa, ngành hoặc khóa để bắt đầu.</p></div>}</div>
      </section>
    </div>}
    {tab === "import" && canImport && <AlumniImportPanel previewMode={previewMode} />}
  </section>;
}

export function ReviewQueuePage({ roles, permissions, previewMode }: { roles: Role[]; permissions: string[]; previewMode: boolean }) {
  const types = useMemo(() => ["ALUMNI", "ALUMNI_CHANGE", ...(roles.includes("ADMIN") || permissions.includes("EMPLOYER_REVIEW") ? ["EMPLOYER"] : []), ...(roles.includes("ADMIN") || permissions.includes("JOB_REVIEW") ? ["JOB"] : []), ...(roles.includes("ADMIN") || permissions.includes("ALUMNI_IMPORT") ? ["IMPORT"] : [])], [roles, permissions]);
  const [type, setType] = useState(types[0] ?? "ALUMNI");
  const [items, setItems] = useState<Review[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  useEffect(() => { if (!types.includes(type)) setType(types[0] ?? "ALUMNI"); }, [types, type]);
  useEffect(() => { if (!previewMode && type !== "IMPORT") void load(); }, [previewMode, type]);
  async function load() {
    setBusy(true); setError("");
    try { setItems(await apiRequest<Review[]>(`/staff/reviews?type=${type}`)); }
    catch (reason) { setError(errorMessage(reason)); setItems([]); }
    finally { setBusy(false); }
  }
  async function decide(item: Review, decision: string, reviewNote: string) {
    setBusy(true); setError(""); setNotice("");
    try {
      await requestCsrf();
      await apiRequest(`/staff/reviews/${item.type}/${item.id}/decision`, "POST", { decision, reviewNote });
      setNotice(`Đã ghi nhận quyết định cho hồ sơ #${item.id}.`); await load();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }
  const typeLabels: Record<string, string> = { ALUMNI: "Hồ sơ ban đầu", ALUMNI_CHANGE: "Thay đổi hồ sơ", EMPLOYER: "Doanh nghiệp", JOB: "Tin tuyển dụng", IMPORT: "Nhập Excel" };
  return <section className="management-page">
    <div className="page-title-row"><div><span className="eyebrow">XÁC MINH & KIỂM DUYỆT</span><h1>Hàng chờ xử lý</h1><p>Xem xét hồ sơ ban đầu, thay đổi học vụ và các mục kiểm duyệt được cấp quyền.</p></div><span className="status-pill"><i />{previewMode ? "XEM TRƯỚC" : "KẾT NỐI API"}</span></div>
    {previewMode && <div className="management-callout">Chế độ xem giao diện không gọi API và không hiển thị dữ liệu mẫu.</div>}
    <div className="management-tabs" role="tablist" aria-label="Loại hàng chờ">{types.map((item) => <button key={item} className={type === item ? "selected" : ""} onClick={() => setType(item)}>{typeLabels[item]}</button>)}</div>
    {error && <p className="inline-error" role="alert">{error}</p>}{notice && <p className="inline-success" role="status">{notice}</p>}
    {type === "IMPORT" ? <AlumniImportPanel previewMode={previewMode} /> : <>
      <div className="queue-toolbar"><span>{busy ? "Đang tải dữ liệu…" : `${items.length} hồ sơ chờ xử lý`}</span><button className="button button-secondary" disabled={busy || previewMode} onClick={() => void load()}>Tải lại hàng chờ</button></div>
      <div className="review-grid">{items.map((item) => <ReviewCard key={`${item.type}-${item.id}`} item={item} busy={busy} onDecision={decide} />)}{!busy && items.length === 0 && <div className="surface empty-state"><strong>Hàng chờ đang trống</strong><p>Danh sách hiển thị theo dữ liệu và quyền hiện được máy chủ trả về.</p></div>}</div>
    </>}
  </section>;
}

function ReviewCard({ item, busy, onDecision }: { item: Review; busy: boolean; onDecision: (item: Review, decision: string, note: string) => Promise<void> }) {
  const [note, setNote] = useState("");
  const correction = "REQUEST_CORRECTION";
  const changeFieldLabels: Record<string, string> = {
    studentCode: "Mã sinh viên mới", fullName: "Họ tên mới", facultyId: "Khoa mới",
    majorId: "Ngành mới", cohortId: "Khóa mới", graduationYear: "Năm tốt nghiệp mới",
  };
  function confirmDecision(decision: string, label: string) {
    if (!window.confirm(`${label} cho mục #${item.id}?`)) return;
    void onDecision(item, decision, note);
  }
  return <article className="surface review-card"><div className="review-card-head"><div><span className="eyebrow">{item.type} · #{item.id}</span><h2>{item.displayName}</h2></div><span className="review-status">{item.status}</span></div>
    {item.email && <p><b>Email tài khoản:</b> {item.email}</p>}
    {item.type === "ALUMNI" && <dl className="review-details"><div><dt>Mã sinh viên</dt><dd>{item.studentCode}</dd></div><div><dt>Khoa / ngành</dt><dd>{item.facultyName}{item.majorName ? ` / ${item.majorName}` : ""}</dd></div><div><dt>Khóa / tốt nghiệp</dt><dd>{item.cohortCode} / {item.graduationYear ?? "—"}</dd></div></dl>}
    {item.type === "ALUMNI_CHANGE" && <><dl className="review-details"><div><dt>Thông tin hiện tại</dt><dd>{item.studentCode} · {item.facultyName}{item.majorName ? ` / ${item.majorName}` : ""} · {item.cohortCode} / {item.graduationYear ?? "—"}</dd></div></dl><h3>Thông tin đề nghị thay đổi</h3><dl className="review-details">{Object.entries(item.proposedValues ?? {}).map(([field, value]) => <div key={field}><dt>{changeFieldLabels[field] ?? field}</dt><dd>{value === null ? "Không có" : String(value)}</dd></div>)}</dl></>}
    {item.type === "EMPLOYER" && <dl className="review-details"><div><dt>Mã số DN / MST</dt><dd>{item.businessRegistrationNumber}</dd></div><div><dt>Website</dt><dd>{item.website || "Không cung cấp"}</dd></div></dl>}
    {item.type === "JOB" && <><p><b>Doanh nghiệp:</b> {item.organizationName || "—"}</p><p className="review-description">{item.jobDescription}</p></>}
    <small>Gửi lúc {item.submittedAt ? new Date(item.submittedAt).toLocaleString("vi-VN") : "—"}</small>
    <label className="field review-note"><span>{item.type === "ALUMNI" ? "Lý do yêu cầu bổ sung" : "Lý do từ chối/yêu cầu bổ sung"}</span><textarea value={note} onChange={(event) => setNote(event.target.value)} maxLength={1000} rows={2} placeholder="Bắt buộc khi yêu cầu bổ sung hoặc từ chối"/></label>
    <div className="management-actions"><button className="button button-primary" disabled={busy} onClick={() => confirmDecision("APPROVE", "Phê duyệt")}>Phê duyệt</button>{item.type !== "ALUMNI" && <button className="button button-secondary" disabled={busy || !note.trim()} onClick={() => confirmDecision("REJECT", "Từ chối")}>Từ chối</button>}{item.type !== "ALUMNI_CHANGE" && <button className="button button-ghost" disabled={busy || !note.trim()} onClick={() => confirmDecision(correction, "Yêu cầu bổ sung")}>Yêu cầu bổ sung</button>}</div>
  </article>;
}

function AlumniImportPanel({ previewMode }: { previewMode: boolean }) {
  const [file, setFile] = useState<File | null>(null);
  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  async function downloadTemplate() {
    setBusy(true); setError("");
    try {
      const { blob, filename } = await apiDownload("/staff/alumni-imports/template", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      const url = URL.createObjectURL(blob); const anchor = document.createElement("a"); anchor.href = url; anchor.download = filename; anchor.click(); URL.revokeObjectURL(url);
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }
  async function preview(event: FormEvent) {
    event.preventDefault(); if (!file || previewMode) return;
    if (!file.name.toLowerCase().endsWith(".xlsx")) { setError("Chỉ nhận tệp .xlsx; không hỗ trợ .xls."); return; }
    if (file.size > 10 * 1024 * 1024) { setError("Tệp vượt quá giới hạn 10 MB."); return; }
    setBusy(true); setError(""); setNotice("");
    try { await requestCsrf(); const data = new FormData(); data.append("file", file); setBatch(await apiRequest<ImportBatch>("/staff/alumni-imports/preview", "POST", data)); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }
  async function commit() {
    if (!batch || previewMode) return;
    if (!window.confirm(`Nhập ${batch.validRows} dòng hợp lệ vào hồ sơ cựu sinh viên? Dòng lỗi sẽ bị bỏ qua.`)) return;
    setBusy(true); setError(""); setNotice("");
    try { await requestCsrf(); const result = await apiRequest<ImportBatch>(`/staff/alumni-imports/${batch.id}/commit`, "POST", {}); setBatch(result); setNotice("Đã hoàn tất nhập các dòng hợp lệ; dòng lỗi được bỏ qua."); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }
  return <section className="surface management-surface import-surface"><div className="surface-head"><div><h2>Nhập danh sách cựu sinh viên</h2><span className="muted">XLSX · tối đa 10 MB và 10.000 dòng dữ liệu · xem trước trước khi ghi.</span></div><button className="button button-secondary" disabled={busy || previewMode} onClick={() => void downloadTemplate()}>Tải mẫu Excel</button></div>
    {previewMode && <div className="management-callout">Để xem trước khi chạy thật, cần đăng nhập bằng tài khoản có quyền ALUMNI_IMPORT.</div>}
    {error && <p className="inline-error" role="alert">{error}</p>}{notice && <p className="inline-success" role="status">{notice}</p>}
    <form className="import-upload" onSubmit={preview}><label className="field"><span>Tệp dữ liệu `.xlsx`</span><input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" onChange={(event) => { setFile(event.target.files?.[0] ?? null); setBatch(null); }} disabled={previewMode}/></label><p className="muted small">Sáu cột theo đúng thứ tự: student_code, full_name, faculty_code, major_code, cohort_code, graduation_year. Không ghi đè mã trùng.</p><button className="button button-primary" disabled={!file || busy || previewMode}>{busy ? "Đang xử lý…" : "Xem trước tệp"}</button></form>
    {batch && <div className="batch-result"><div className="batch-summary"><div><span>Lô nhập #{batch.id}</span><strong>{batch.status}</strong></div><div><span>Tổng dòng</span><strong>{batch.totalRows}</strong></div><div><span>Hợp lệ</span><strong>{batch.validRows}</strong></div><div><span>Lỗi, sẽ bỏ qua</span><strong>{batch.skippedRows}</strong></div></div>
      {batch.status.startsWith("PREVIEW") && <div className="management-actions"><button className="button button-primary" disabled={busy || batch.validRows === 0} onClick={() => void commit()}>Xác nhận nhập {batch.validRows} dòng hợp lệ</button><span className="muted small">Bản xem trước hết hạn {new Date(batch.expiresAt).toLocaleString("vi-VN")}.</span></div>}
      {batch.previewRows.length > 0 && <div className="table-scroll"><table className="management-table"><thead><tr><th>Dòng</th><th>Mã sinh viên</th><th>Họ tên</th><th>Khoa</th><th>Ngành</th><th>Khóa</th><th>Năm tốt nghiệp</th></tr></thead><tbody>{batch.previewRows.map((row) => <tr key={row.rowNumber}><td>{row.rowNumber}</td><td>{row.studentCode}</td><td>{row.fullName}</td><td>{row.facultyCode}</td><td>{row.majorCode || "—"}</td><td>{row.cohortCode}</td><td>{row.graduationYear ?? "—"}</td></tr>)}</tbody></table></div>}
      {batch.errors.length > 0 && <div className="import-errors"><h3>Lỗi theo dòng</h3><ul>{batch.errors.map((item, index) => <li key={`${item.rowNumber}-${index}`}>Dòng {item.rowNumber}: {item.message}</li>)}</ul></div>}
    </div>}
  </section>;
}

function toggle(values: string[], value: string): string[] { return values.includes(value) ? values.filter((item) => item !== value) : [...values, value]; }
function toggleNumber(values: number[], value: number): number[] { return values.includes(value) ? values.filter((item) => item !== value) : [...values, value]; }
