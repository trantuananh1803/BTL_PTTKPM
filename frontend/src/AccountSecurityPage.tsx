import { useState, type FormEvent } from "react";
import { apiRequest, errorMessage, requestCsrf } from "./api";

export default function AccountSecurityPage({ previewMode }: { previewMode: boolean }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function changePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (previewMode) return;
    const form = event.currentTarget;
    const values = new FormData(form);
    const currentPassword = String(values.get("currentPassword") ?? "");
    const newPassword = String(values.get("newPassword") ?? "");
    const confirmPassword = String(values.get("confirmPassword") ?? "");
    if (Array.from(newPassword).length < 12 || new TextEncoder().encode(newPassword).length > 72) {
      setError("Mật khẩu mới cần ít nhất 12 ký tự và không vượt quá 72 byte UTF-8.");
      setNotice("");
      return;
    }
    if (newPassword !== confirmPassword) {
      setError("Mật khẩu nhập lại chưa khớp.");
      setNotice("");
      return;
    }

    setBusy(true);
    setError("");
    setNotice("");
    try {
      await requestCsrf();
      await apiRequest<void>("/me/password", "POST", { currentPassword, newPassword });
      form.reset();
      setNotice("Máy chủ đã xác nhận đổi mật khẩu. Nếu phiên bị thu hồi, hãy đăng nhập lại.");
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setBusy(false);
    }
  }

  return <>
    <div className="page-title-row"><div><span className="eyebrow">TÀI KHOẢN</span><h1>Bảo mật tài khoản</h1><p>Đổi mật khẩu cho tài khoản đang đăng nhập. Mật khẩu được kiểm tra lại ở máy chủ.</p></div><span className="status-pill"><i />{previewMode ? "XEM TRƯỚC" : "THEO PHIÊN"}</span></div>
    {previewMode && <div className="management-callout">Minh họa thiết kế: không gọi API, không lưu thông tin và không hiển thị dữ liệu mẫu.</div>}
    {error && <p className="inline-error" role="alert">{error}</p>}
    {notice && <p className="inline-success" role="status">{notice}</p>}
    <section className="surface management-surface" style={{ maxWidth: 760 }}>
      <div className="surface-head"><div><h2>Đổi mật khẩu</h2><span className="muted">Tối thiểu 12 ký tự Unicode, tối đa 72 byte UTF-8; lưu bằng BCrypt.</span></div></div>
      <form className="management-form" onSubmit={(event) => void changePassword(event)}>
        <label className="field"><span>Mật khẩu hiện tại *</span><input name="currentPassword" type="password" autoComplete="current-password" required disabled={previewMode || busy} /></label>
        <div className="field-grid">
          <label className="field"><span>Mật khẩu mới *</span><input name="newPassword" type="password" autoComplete="new-password" minLength={12} required disabled={previewMode || busy} /></label>
          <label className="field"><span>Nhập lại mật khẩu mới *</span><input name="confirmPassword" type="password" autoComplete="new-password" minLength={12} required disabled={previewMode || busy} /></label>
        </div>
        <div className="management-actions"><button className="button button-primary" disabled={previewMode || busy}>{busy ? "Đang đổi mật khẩu…" : "Đổi mật khẩu"}</button></div>
      </form>
    </section>
  </>;
}
