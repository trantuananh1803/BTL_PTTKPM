import { useEffect, useMemo, useState, type ChangeEvent, type FormEvent } from "react";
import { apiRequest, errorMessage, requestCsrf } from "./api";
import AccountSecurityPage from "./AccountSecurityPage";
import AlumniProfilePage from "./AlumniProfilePage";
import { AlumniManagementPage, EmploymentSurveyPage } from "./AlumniWorkPages";
import { AdminConsolePage, ReviewQueuePage } from "./ManagementPages";
import { EmployerJobsPage, JobsPage } from "./JobsPages";
import { EventsPage } from "./EventPages";
import { ReportsPage } from "./ReportsPage";

type Role = "ALUMNI" | "STUDENT" | "EMPLOYER" | "FACULTY_STAFF" | "ADMIN";
type User = { id: number; fullName: string; email: string; roles: Role[]; permissions: string[] };
type FieldOption = string | { value: string; label: string };
type AuthField = {
  name: string;
  label: string;
  type?: "text" | "email" | "password" | "tel" | "number";
  required?: boolean;
  options?: FieldOption[];
  onChange?: (event: ChangeEvent<HTMLSelectElement | HTMLInputElement>) => void;
  value?: string;
  inputMode?: "text" | "numeric" | "email";
  autoComplete?: string;
  maxLength?: number;
  pattern?: string;
  min?: number;
  max?: number;
};
type ModuleId = "home" | "security" | "profile" | "employment" | "jobs" | "employer" | "events" | "review" | "directory" | "admin" | "reports";
type SignupCatalogs = {
  faculties: Array<{ id: number; code: string; name: string }>;
  majors: Array<{ id: number; facultyId: number; code: string; name: string }>;
  cohorts: Array<{ id: number; code: string; admissionYear: number; expectedGraduationYear: number }>;
};
type DemoAccountHint = { role: Role; roleName: string; email: string };
type DemoAccountHints = { accounts: DemoAccountHint[]; sharedPassword: string };

const roleNames: Record<Role, string> = {
  ALUMNI: "Cựu sinh viên",
  STUDENT: "Sinh viên",
  EMPLOYER: "Nhà tuyển dụng",
  FACULTY_STAFF: "Cán bộ quản lý",
  ADMIN: "Quản trị viên",
};
const demoRoleOrder: Role[] = ["ALUMNI", "STUDENT", "EMPLOYER", "FACULTY_STAFF", "ADMIN"];

const modules: { id: ModuleId; title: string; eyebrow: string; icon: string; roles: Role[] }[] = [
  { id: "home", title: "Tổng quan", eyebrow: "WORKSPACE", icon: "⌂", roles: ["ALUMNI", "STUDENT", "EMPLOYER", "FACULTY_STAFF", "ADMIN"] },
  { id: "security", title: "Tài khoản & bảo mật", eyebrow: "TÀI KHOẢN", icon: "◇", roles: ["ALUMNI", "STUDENT", "EMPLOYER", "FACULTY_STAFF", "ADMIN"] },
  { id: "profile", title: "Hồ sơ cá nhân", eyebrow: "CÁ NHÂN", icon: "◉", roles: ["ALUMNI"] },
  { id: "employment", title: "Khảo sát việc làm", eyebrow: "CÁ NHÂN", icon: "↗", roles: ["ALUMNI"] },
  { id: "jobs", title: "Việc làm", eyebrow: "KẾT NỐI", icon: "▤", roles: ["ALUMNI", "STUDENT"] },
  { id: "employer", title: "Tuyển dụng", eyebrow: "DOANH NGHIỆP", icon: "▣", roles: ["EMPLOYER"] },
  { id: "events", title: "Sự kiện & thông báo", eyebrow: "CỘNG ĐỒNG", icon: "◷", roles: ["ALUMNI", "STUDENT", "EMPLOYER", "FACULTY_STAFF", "ADMIN"] },
  { id: "review", title: "Xác minh & duyệt", eyebrow: "QUẢN LÝ", icon: "✓", roles: ["FACULTY_STAFF", "ADMIN"] },
  { id: "directory", title: "Cựu sinh viên", eyebrow: "QUẢN LÝ", icon: "◎", roles: ["FACULTY_STAFF", "ADMIN"] },
  { id: "admin", title: "Quản trị hệ thống", eyebrow: "HỆ THỐNG", icon: "⚙", roles: ["ADMIN"] },
  { id: "reports", title: "Báo cáo & thống kê", eyebrow: "HỆ THỐNG", icon: "▥", roles: ["FACULTY_STAFF", "ADMIN"] },
];

function App() {
  const previewMode = new URLSearchParams(window.location.search).get("ui-preview") === "1";
  const [user, setUser] = useState<User | null>(null);
  const [active, setActive] = useState<ModuleId>("home");
  const [authMode, setAuthMode] = useState<"login" | "register" | "recover" | "otp">("login");
  const [authPurpose, setAuthPurpose] = useState<"register" | "recover">("register");
  const [pendingEmail, setPendingEmail] = useState("");
  const [previewRole, setPreviewRole] = useState<Role>("ALUMNI");
  const [signupRole, setSignupRole] = useState<"ALUMNI" | "STUDENT" | "EMPLOYER">("ALUMNI");
  const [signupFacultyId, setSignupFacultyId] = useState("");
  const [signupMajorId, setSignupMajorId] = useState("");
  const [signupCatalogs, setSignupCatalogs] = useState<SignupCatalogs | null>(null);
  const [demoAccounts, setDemoAccounts] = useState<DemoAccountHints | null>(null);
  const [loginEmail, setLoginEmail] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [catalogLoadError, setCatalogLoadError] = useState("");
  const [busy, setBusy] = useState(false);
  const [authMessage, setAuthMessage] = useState<{ kind: "error" | "success" | "info"; text: string } | null>(null);
  const [logoutError, setLogoutError] = useState("");
  const [eventsInitialTab, setEventsInitialTab] = useState<"events" | "inbox">("events");
  const [eventsNavigationKey, setEventsNavigationKey] = useState(0);

  const roles = previewMode ? [previewRole] : user?.roles ?? [];
  const permissions = user?.permissions ?? [];
  const visibleModules = useMemo(() => modules.filter((item) => item.roles.some((role) => roles.includes(role))), [roles]);
  const current = modules.find((item) => item.id === active);
  const roleLabel = roles.map((role) => roleNames[role]).join(" · ") || "Người dùng";
  const displayName = previewMode ? roleLabel : user?.fullName ?? "Người dùng";

  useEffect(() => {
    if (previewMode) return;
    requestCsrf().catch(() => undefined);
    apiRequest<User>("/auth/me").then(setUser).catch(() => setUser(null));
    apiRequest<SignupCatalogs>("/public/catalogs").then(setSignupCatalogs).catch((reason) => setCatalogLoadError(errorMessage(reason)));
    if (import.meta.env.DEV) {
      apiRequest<DemoAccountHints>("/auth/demo-accounts")
        .then((hints) => setDemoAccounts({ ...hints, accounts: hints.accounts.sort((left, right) => demoRoleOrder.indexOf(left.role) - demoRoleOrder.indexOf(right.role)) }))
        .catch(() => setDemoAccounts(null));
    }
  }, [previewMode]);

  useEffect(() => {
    if (!visibleModules.some((item) => item.id === active)) setActive("home");
  }, [active, visibleModules]);

  async function submitAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setAuthMessage(null);
    const data = new FormData(event.currentTarget);
    const body = Object.fromEntries(data.entries());
    try {
      if (authMode === "otp") {
        const otpPath = authPurpose === "register" ? "/auth/register/complete" : "/auth/password-recovery/complete";
        const password = String(body.password ?? "");
        if (!/^\d{8}$/.test(String(body.otp ?? ""))) {
          setAuthMessage({ kind: "error", text: "Mã OTP cần gồm đúng 8 chữ số." });
          return;
        }
        if (Array.from(password).length < 12 || new TextEncoder().encode(password).length > 72) {
          setAuthMessage({ kind: "error", text: "Mật khẩu cần ít nhất 12 ký tự và không vượt quá 72 byte UTF-8." });
          return;
        }
        if (password !== String(body.confirmPassword ?? "")) {
          setAuthMessage({ kind: "error", text: "Mật khẩu nhập lại chưa khớp." });
          return;
        }
        await requestCsrf();
        const completion = authPurpose === "register"
          ? { email: pendingEmail, otp: body.otp, password }
          : { email: pendingEmail, otp: body.otp, newPassword: password };
        await apiRequest(otpPath, "POST", completion);
        setAuthMessage({ kind: "success", text: authPurpose === "register" ? "Máy chủ đã xác nhận tài khoản. Bạn có thể đăng nhập." : "Máy chủ đã xác nhận mật khẩu mới. Bạn có thể đăng nhập." });
        setAuthMode("login");
      } else if (authMode === "recover") {
        setPendingEmail(String(body.email ?? ""));
        setAuthPurpose("recover");
        await requestCsrf();
        await apiRequest<{ message: string }>("/auth/password-recovery-requests", "POST", body);
        setAuthMessage({ kind: "info", text: "Máy chủ đã tiếp nhận yêu cầu. Giao diện không xác nhận email đã đến; chỉ nhập OTP nếu bạn thực sự nhận được mã." });
        setAuthMode("otp");
      } else if (authMode === "register") {
        const role = String(body.role ?? "") as "ALUMNI" | "STUDENT" | "EMPLOYER";
        const registrationBody: Record<string, unknown> = { fullName: body.fullName, email: body.email, role };
        if (role === "ALUMNI") {
          registrationBody.studentCode = body.studentCode;
          registrationBody.facultyId = Number(body.facultyId);
          registrationBody.cohortId = Number(body.cohortId);
          registrationBody.graduationYear = Number(body.graduationYear);
          if (body.majorId) registrationBody.majorId = Number(body.majorId);
        }
        if (role === "EMPLOYER") {
          registrationBody.organizationName = body.organizationName;
          registrationBody.businessRegistrationNumber = body.businessRegistrationNumber;
          if (body.website) registrationBody.website = body.website;
        }
        setPendingEmail(String(body.email ?? ""));
        setAuthPurpose("register");
        await requestCsrf();
        await apiRequest<{ message: string }>("/auth/register", "POST", registrationBody);
        setAuthMessage({ kind: "info", text: "Máy chủ đã tiếp nhận yêu cầu. Giao diện không xác nhận email đã đến; chỉ nhập OTP nếu bạn thực sự nhận được mã." });
        setAuthMode("otp");
      } else {
        await requestCsrf();
        const session = await apiRequest<User>("/auth/login", "POST", body);
        setUser(session);
        setAuthMessage(null);
        setActive("home");
      }
    } catch (reason) {
      setAuthMessage({ kind: "error", text: errorMessage(reason) });
    } finally {
      setBusy(false);
    }
  }

  async function logout() {
    setLogoutError("");
    try {
      await apiRequest<void>("/auth/logout", "POST", {});
      setUser(null);
      setActive("home");
    } catch (reason) {
      setLogoutError(errorMessage(reason));
    }
  }

  async function resendOtp() {
    setBusy(true);
    setAuthMessage(null);
    try {
      await requestCsrf();
      const path = authPurpose === "register" ? "/auth/register/resend-otp" : "/auth/password-recovery/resend-otp";
      await apiRequest<{ message: string }>(path, "POST", { email: pendingEmail });
      setAuthMessage({ kind: "info", text: "Máy chủ đã tiếp nhận yêu cầu gửi lại. Giao diện không xác nhận email đã đến; chỉ nhập OTP nếu bạn thực sự nhận được mã." });
    } catch (reason) {
      setAuthMessage({ kind: "error", text: errorMessage(reason) });
    } finally {
      setBusy(false);
    }
  }

  if (!previewMode && !user) {
    const showSignupCatalogs = authMode === "register" && signupRole === "ALUMNI";
    return <main className="auth-layout">
      <section className="auth-story">
        <div className="brand brand-light"><span className="brand-mark">P</span><span>PHENIKAA <small>ALUMNI NETWORK</small></span></div>
        <div className="story-content"><span className="eyebrow light">KẾT NỐI · PHÁT TRIỂN · LAN TỎA</span><h1>Một hành trình.<br /><em>Vạn kết nối.</em></h1><p>Không gian dành cho cộng đồng cựu sinh viên Phenikaa — nơi những câu chuyện tiếp tục được viết nên.</p><div className="story-tags"><span>Hồ sơ học tập</span><span>Cơ hội nghề nghiệp</span><span>Sự kiện cộng đồng</span></div></div>
        <div className="story-footer">PHENIKAA UNIVERSITY <span>·</span> HANOI, VIETNAM</div>
      </section>
      <section className="auth-panel"><div className="auth-card">
        <span className="eyebrow">CỔNG THÔNG TIN CỰU SINH VIÊN</span>
        <h2>{authMode === "login" ? "Chào mừng trở lại" : authMode === "register" ? "Tạo tài khoản" : authMode === "otp" ? "Xác minh tài khoản" : "Khôi phục mật khẩu"}</h2>
        <p className="muted">{authMode === "login" ? "Đăng nhập để tiếp tục kết nối với cộng đồng." : authMode === "register" ? "Đăng ký cựu sinh viên, sinh viên hoặc nhà tuyển dụng bằng email." : "Hoàn tất các bước bảo mật theo hướng dẫn trên màn hình."}</p>
        <form onSubmit={(event) => void submitAuth(event)} className="form-stack">
          {authMode === "register" && <FieldInput field={{ name: "fullName", label: "Họ và tên", required: true, autoComplete: "name", maxLength: 180 }} />}
          {authMode !== "otp" && <FieldInput field={{ name: "email", label: "Email", type: "email", required: true, autoComplete: "email", maxLength: 254, ...(authMode === "login" ? { value: loginEmail, onChange: (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setLoginEmail(event.target.value) } : {}) }} />}
          {authMode === "otp" && <input type="hidden" name="email" value={pendingEmail} />}
          {authMode === "login" && <FieldInput field={{ name: "password", label: "Mật khẩu", type: "password", required: true, autoComplete: "current-password", value: loginPassword, onChange: (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setLoginPassword(event.target.value) }} />}
          {authMode === "register" && <label className="field" htmlFor="signup-role"><span>Bạn là <b>*</b></span><select id="signup-role" name="role" required value={signupRole} onChange={(event) => { setSignupRole(event.target.value as typeof signupRole); setSignupMajorId(""); }}><option value="ALUMNI">Cựu sinh viên</option><option value="STUDENT">Sinh viên</option><option value="EMPLOYER">Nhà tuyển dụng</option></select></label>}
          {showSignupCatalogs && <>
            <FieldInput field={{ name: "studentCode", label: "Mã sinh viên", required: true, maxLength: 32 }} />
            <FieldInput field={{ name: "facultyId", label: "Khoa", required: true, value: signupFacultyId, onChange: (event) => { setSignupFacultyId(event.target.value); setSignupMajorId(""); }, options: (signupCatalogs?.faculties ?? []).map((item) => ({ value: String(item.id), label: item.name })) }} />
            <FieldInput field={{ name: "majorId", label: "Ngành (không bắt buộc)", value: signupMajorId, onChange: (event) => setSignupMajorId(event.target.value), options: (signupCatalogs?.majors ?? []).filter((item) => !signupFacultyId || String(item.facultyId) === signupFacultyId).map((item) => ({ value: String(item.id), label: item.name })) }} />
            <FieldInput field={{ name: "cohortId", label: "Khóa", required: true, options: (signupCatalogs?.cohorts ?? []).map((item) => ({ value: String(item.id), label: item.code })) }} />
            <FieldInput field={{ name: "graduationYear", label: "Năm tốt nghiệp", type: "number", required: true, min: 1900, max: 2200 }} />
          </>}
          {authMode === "register" && signupRole === "EMPLOYER" && <>
            <FieldInput field={{ name: "organizationName", label: "Tên doanh nghiệp", required: true, maxLength: 180 }} />
            <FieldInput field={{ name: "businessRegistrationNumber", label: "Mã số doanh nghiệp / MST", required: true, maxLength: 80 }} />
            <FieldInput field={{ name: "website", label: "Website (không bắt buộc)", maxLength: 255 }} />
          </>}
          {showSignupCatalogs && catalogLoadError && <p className="inline-error" role="alert">Không tải được danh mục khoa/ngành/khóa: {catalogLoadError}</p>}
          {showSignupCatalogs && !signupCatalogs && !catalogLoadError && <p className="muted small" role="status">Đang tải danh mục học vụ…</p>}
          {authMode === "otp" && <FieldInput field={{ name: "otp", label: "Mã OTP 8 chữ số", type: "text", required: true, inputMode: "numeric", autoComplete: "one-time-code", maxLength: 8, pattern: "[0-9]{8}" }} />}
          {authMode === "otp" && <FieldInput field={{ name: "password", label: authPurpose === "recover" ? "Mật khẩu mới" : "Mật khẩu", type: "password", required: true, autoComplete: "new-password" }} />}
          {authMode === "otp" && <FieldInput field={{ name: "confirmPassword", label: "Nhập lại mật khẩu", type: "password", required: true, autoComplete: "new-password" }} />}
          <button className="button button-primary button-full" disabled={busy || (showSignupCatalogs && !signupCatalogs)}>{busy ? "Đang xử lý…" : authMode === "login" ? "Đăng nhập" : authMode === "register" ? "Đăng ký tài khoản" : authMode === "otp" ? "Xác minh và tiếp tục" : "Gửi yêu cầu khôi phục"}<span>→</span></button>
        </form>
        {authMode === "login" && demoAccounts && demoAccounts.accounts.length > 0 && <section className="demo-account-suggestions" aria-labelledby="demo-account-title">
          <div><strong id="demo-account-title">Tài khoản demo theo vai trò</strong><p>Mật khẩu dùng chung: <code>{demoAccounts.sharedPassword}</code></p></div>
          <div className="demo-account-list">
            {demoAccounts.accounts.map((account) => <button type="button" key={account.role} onClick={() => { setLoginEmail(account.email); setLoginPassword(demoAccounts.sharedPassword); }}>
              <span>{account.roleName}</span><small>{account.email}</small>
            </button>)}
          </div>
        </section>}
        {authMessage && <p className={`notice ${authMessage.kind === "error" ? "notice-error" : authMessage.kind === "success" ? "notice-success" : "notice-info"}`} role={authMessage.kind === "error" ? "alert" : "status"}>{authMessage.text}</p>}
        {authMode === "otp" && <button type="button" className="text-button" onClick={() => void resendOtp()} disabled={busy}>Gửi lại mã xác minh</button>}
        <div className="auth-links">{authMode === "login" ? <><button type="button" onClick={() => { setAuthPurpose("recover"); setAuthMode("recover"); setAuthMessage(null); }}>Quên mật khẩu?</button><span>Chưa có tài khoản? <button type="button" onClick={() => { setAuthPurpose("register"); setAuthMode("register"); setAuthMessage(null); }}>Đăng ký</button></span></> : <button type="button" onClick={() => { setAuthMode("login"); setAuthMessage(null); }}>← Quay lại đăng nhập</button>}</div>
        <p className="privacy-note">Không chia sẻ mã OTP hoặc mật khẩu với người khác.</p>
      </div></section>
    </main>;
  }

  const setEventsTab = (tab: "events" | "inbox") => { setEventsInitialTab(tab); setEventsNavigationKey((key) => key + 1); setActive("events"); };
  const page = active === "home" ? <HomePage roles={roles} setActive={setActive} />
    : active === "security" ? <AccountSecurityPage previewMode={previewMode} />
      : active === "profile" ? <AlumniProfilePage previewMode={previewMode} />
        : active === "employment" ? <EmploymentSurveyPage previewMode={previewMode} />
          : active === "jobs" ? <JobsPage previewMode={previewMode} />
            : active === "employer" ? <EmployerJobsPage previewMode={previewMode} />
              : active === "events" ? <EventsPage key={eventsNavigationKey} roles={roles} previewMode={previewMode} initialTab={eventsInitialTab} />
                : active === "directory" ? <AlumniManagementPage previewMode={previewMode} isAdmin={roles.includes("ADMIN")} />
                  : active === "review" ? <ReviewQueuePage roles={roles} permissions={permissions} previewMode={previewMode} />
                    : active === "admin" ? <AdminConsolePage previewMode={previewMode} permissions={permissions} />
                      : <ReportsPage previewMode={previewMode} />;

  return <div className="app-shell">
    <a className="skip-link" href="#main-content">Bỏ qua điều hướng, tới nội dung chính</a>
    {previewMode && <div className="preview-banner"><strong>Chế độ xem trước</strong><span>Không gọi API, không hiển thị dữ liệu giả và không lưu thay đổi.</span><label>Vai trò <select value={previewRole} onChange={(event) => { setPreviewRole(event.target.value as Role); setActive("home"); }}><option value="ALUMNI">Cựu sinh viên</option><option value="STUDENT">Sinh viên</option><option value="EMPLOYER">Nhà tuyển dụng</option><option value="FACULTY_STAFF">Cán bộ quản lý</option><option value="ADMIN">Quản trị viên</option></select></label></div>}
    <aside className="sidebar" aria-label="Điều hướng chính">
      <div className="brand"><span className="brand-mark">P</span><span>PHENIKAA <small>ALUMNI NETWORK</small></span></div>
      <nav className="side-scroll" aria-label="Các chức năng">
        {visibleModules.map((item, index) => {
          const previous = visibleModules[index - 1];
          return <div key={item.id}>{item.eyebrow !== previous?.eyebrow && <div className="nav-label">{item.eyebrow}</div>}<button className={`nav-item ${active === item.id ? "active" : ""}`} aria-current={active === item.id ? "page" : undefined} onClick={() => setActive(item.id)}><span className="nav-icon" aria-hidden="true">{item.icon}</span>{item.title}</button></div>;
        })}
      </nav>
      <div className="sidebar-bottom"><div className="side-foot">© PHENIKAA UNIVERSITY<br />ALUMNI NETWORK · 2026</div></div>
    </aside>
    <main className="main-area" id="main-content" tabIndex={-1}>
      <header className="topbar"><div className="breadcrumbs"><span>Không gian</span><b>/</b><strong>{current?.title ?? "Tổng quan"}</strong></div><div className="top-actions"><button className="icon-button" aria-label="Mở hộp thông báo" onClick={() => setEventsTab("inbox")}>♧</button><span className="top-divider" /><div className="user-chip"><span className="avatar" aria-hidden="true">{displayName.slice(0, 1).toUpperCase()}</span><span><strong>{displayName}</strong><small>{previewMode ? "MINH HỌA GIAO DIỆN" : roleLabel}</small></span></div>{!previewMode && <button className="logout-button" onClick={() => void logout()}>Đăng xuất</button>}</div></header>
      <div className="page-wrap">
        {logoutError && <div className="inline-error" role="alert">{logoutError}</div>}
        {page}
        <footer className="page-footer"><span>PHENIKAA ALUMNI NETWORK</span><span>Dữ liệu hiển thị theo vai trò và phạm vi được cấp</span></footer>
      </div>
    </main>
  </div>;
}

function HomePage({ roles, setActive }: { roles: Role[]; setActive: (id: ModuleId) => void }) {
  const cards = modules.filter((item) => item.id !== "home" && item.id !== "security" && item.roles.some((role) => roles.includes(role))).slice(0, 4);
  const primaryTarget: ModuleId = roles.includes("ALUMNI") ? "profile" : roles.includes("EMPLOYER") ? "employer" : roles.includes("FACULTY_STAFF") || roles.includes("ADMIN") ? "directory" : "jobs";
  const primaryAction = primaryTarget === "profile" ? "Cập nhật hồ sơ" : primaryTarget === "directory" ? "Quản lý hồ sơ" : primaryTarget === "employer" ? "Mở trung tâm tuyển dụng" : "Khám phá việc làm";
  const greeting = roles.length === 1 && roles[0] === "ALUMNI" ? "Chào mừng trở lại" : "Không gian làm việc của bạn";
  return <>
    <section className="welcome-card"><div><span className="eyebrow light">{new Intl.DateTimeFormat("vi-VN", { weekday: "long", day: "numeric", month: "long", year: "numeric" }).format(new Date())}</span><h1>{greeting}<br /><em>cộng đồng Phenikaa.</em></h1><p>Kết nối với cộng đồng, cập nhật hành trình nghề nghiệp và khám phá các hoạt động phù hợp với vai trò của bạn.</p><button className="button button-light" onClick={() => setActive(cards[0]?.id ?? "security")}>Mở chức năng <span>→</span></button></div><div className="welcome-art" aria-hidden="true"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="orbit-center">P</div><div className="orbit-node node-a">✦</div><div className="orbit-node node-b">◉</div><div className="orbit-node node-c">↗</div></div></section>
    <div className="section-heading"><div><span className="eyebrow">BẮT ĐẦU TỪ ĐÂY</span><h2>Không gian của bạn</h2></div><span className="muted small">Các tính năng đã được cấp cho tài khoản</span></div>
    <div className="quick-grid">{cards.map((card, index) => <button className="quick-card" key={card.id} onClick={() => setActive(card.id)}><span className={`quick-icon color-${index}`} aria-hidden="true">{card.icon}</span><span className="quick-title">{card.title}</span><span className="quick-desc">{quickDescription(card.id)}</span><span className="quick-arrow" aria-hidden="true">↗</span></button>)}</div>
    <section className="connection-card"><div className="connection-graphic" aria-hidden="true"><span>✦</span><span>◉</span><span>✳</span><span>↗</span><div /></div><div><span className="eyebrow">CỘNG ĐỒNG PHENIKAA</span><h2>Kết nối để cùng<br />tiến xa hơn.</h2><p>{primaryTarget === "profile" ? "Cập nhật thông tin hồ sơ và lựa chọn phạm vi chia sẻ." : primaryTarget === "directory" ? "Tra cứu hồ sơ trong phạm vi công việc được giao." : primaryTarget === "employer" ? "Quản lý tin tuyển dụng và hồ sơ ứng tuyển của doanh nghiệp." : "Tìm kiếm cơ hội nghề nghiệp phù hợp."}</p><button className="text-button" onClick={() => setActive(primaryTarget)}>{primaryAction} <span>→</span></button></div></section>
  </>;
}

function quickDescription(id: ModuleId): string {
  return ({ security: "Đổi mật khẩu và bảo vệ tài khoản", profile: "Thông tin cá nhân và thiết lập chia sẻ", employment: "Cập nhật hành trình nghề nghiệp", jobs: "Khám phá việc làm phù hợp", employer: "Đăng tin và theo dõi ứng viên", events: "Hoạt động và thông báo cộng đồng", review: "Xác minh hồ sơ và kiểm duyệt", directory: "Tìm kiếm hồ sơ cựu sinh viên", admin: "Tài khoản và danh mục hệ thống", reports: "Số liệu và báo cáo tổng hợp" } as Partial<Record<ModuleId, string>>)[id] ?? "Truy cập các chức năng dành cho bạn";
}

function FieldInput({ field }: { field: AuthField }) {
  const common = { id: field.name, name: field.name, required: field.required, "aria-label": field.label, autoComplete: field.autoComplete, maxLength: field.maxLength, inputMode: field.inputMode, pattern: field.pattern, min: field.min, max: field.max, value: field.value, onChange: field.onChange };
  return <label className="field" htmlFor={field.name}><span>{field.label}{field.required && <b> *</b>}</span>{field.options ? <select {...common}><option value="">Chọn {field.label.toLowerCase()}</option>{field.options.map((option) => typeof option === "string" ? <option key={option} value={option}>{option}</option> : <option key={option.value} value={option.value}>{option.label}</option>)}</select> : <input {...common} type={field.type ?? "text"} placeholder={field.type === "password" ? "" : `Nhập ${field.label.toLowerCase()}`} />}</label>;
}

export default App;
