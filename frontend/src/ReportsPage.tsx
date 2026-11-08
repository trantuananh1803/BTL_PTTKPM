import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { apiDownload, apiRequest, errorMessage } from "./api";
import "./ReportsPage.css";

type Faculty = { id: number; code: string; name: string };
type Major = { id: number; facultyId: number; code: string; name: string };
type Cohort = { id: number; code: string; admissionYear: number; expectedGraduationYear: number };
type FilterOptions = { faculties: Faculty[]; majors: Major[]; cohorts: Cohort[] };
type Filters = { facultyId: string; majorId: string; cohortId: string; graduationYear: string; employmentStatus: string };
type StatusMetric = { code: "EMPLOYED" | "SEEKING" | "STUDYING" | "OTHER" | "UNKNOWN"; count: number; percentage: number | null; denominator: string };
type Report = {
  generatedAt: string;
  appliedFilters: { facultyId: number | null; majorId: number | null; cohortId: number | null; graduationYear: number | null; employmentStatus: string | null };
  totalProfiles: number;
  verifiedCount: number;
  pendingCount: number;
  reportedCount: number;
  unknownCount: number;
  responseCoverage: number | null;
  employmentStatuses: StatusMetric[];
};

const emptyFilters: Filters = { facultyId: "", majorId: "", cohortId: "", graduationYear: "", employmentStatus: "" };
const statusNames: Record<StatusMetric["code"], string> = {
  EMPLOYED: "Đang làm việc",
  SEEKING: "Đang tìm việc",
  STUDYING: "Đang học tiếp",
  OTHER: "Khác",
  UNKNOWN: "UNKNOWN / chưa trả lời",
};
const xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export function ReportsPage({ previewMode }: { previewMode: boolean }) {
  const [options, setOptions] = useState<FilterOptions | null>(null);
  const [filters, setFilters] = useState<Filters>(emptyFilters);
  const [report, setReport] = useState<Report | null>(null);
  const [loading, setLoading] = useState(false);
  const [downloading, setDownloading] = useState<"pdf" | "xlsx" | null>(null);
  const [error, setError] = useState("");

  const query = useMemo(() => {
    const params = new URLSearchParams();
    (Object.keys(filters) as Array<keyof Filters>).forEach((key) => {
      if (filters[key].trim()) params.set(key, filters[key].trim());
    });
    return params.toString();
  }, [filters]);

  const loadReport = useCallback(async (queryString: string) => {
    setLoading(true);
    setError("");
    try {
      const value = await apiRequest<Report>(`/reports/alumni${queryString ? `?${queryString}` : ""}`);
      setReport(value);
    } catch (reason) {
      setError(errorMessage(reason));
      setReport(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (previewMode) return;
    let active = true;
    void apiRequest<FilterOptions>("/reports/filters")
      .then((value) => {
        if (active) setOptions(value);
      })
      .catch((reason) => {
        if (active) setError(errorMessage(reason));
      });
    void loadReport("");
    return () => { active = false; };
  }, [loadReport, previewMode]);

  const visibleMajors = options?.majors.filter((major) => !filters.facultyId || major.facultyId === Number(filters.facultyId)) ?? [];
  const applied = report?.appliedFilters;
  const appliedNames = applied && options ? [
    applied.facultyId ? options.faculties.find((item) => item.id === applied.facultyId)?.name : null,
    applied.majorId ? options.majors.find((item) => item.id === applied.majorId)?.name : null,
    applied.cohortId ? options.cohorts.find((item) => item.id === applied.cohortId)?.code : null,
    applied.graduationYear ? `Năm tốt nghiệp ${applied.graduationYear}` : null,
    applied.employmentStatus ? statusNames[applied.employmentStatus as StatusMetric["code"]] : null,
  ].filter(Boolean) as string[] : [];

  async function exportReport(format: "pdf" | "xlsx") {
    if (!report) return;
    setDownloading(format);
    setError("");
    try {
      const expectedMime = format === "pdf" ? "application/pdf" : xlsxMime;
      const file = await apiDownload(`/reports/alumni/export?${query ? `${query}&` : ""}format=${format}`, expectedMime);
      const url = URL.createObjectURL(file.blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = file.filename;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setDownloading(null);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void loadReport(query);
  }

  return <>
    <div className="page-title-row reports-title"><div><span className="eyebrow">PHÂN TÍCH DỮ LIỆU</span><h1>Báo cáo & thống kê</h1><p>Thống kê tổng hợp theo khoa, ngành, khóa và tình trạng việc làm.</p></div><span className="status-pill"><i /> {previewMode ? "XEM TRƯỚC" : "THEO PHIÊN"}</span></div>
    {previewMode ? <section className="surface report-empty"><strong>Giao diện xem trước</strong><p>Báo cáo không dùng dữ liệu giả. Đăng nhập bằng tài khoản cán bộ hoặc quản trị viên để tải số liệu.</p></section> : <>
      <form className="surface report-filter" onSubmit={submit}>
        <div className="report-section-heading"><div><span className="eyebrow">BỘ LỌC</span><h2>Phạm vi báo cáo</h2></div><span className="report-scope">Cán bộ: khoa được phân công · Quản trị viên: toàn trường</span></div>
        <div className="report-filter-grid">
          <label className="report-field"><span>Khoa</span><select value={filters.facultyId} onChange={(event) => setFilters((previous) => ({ ...previous, facultyId: event.target.value, majorId: "" }))}><option value="">Tất cả trong phạm vi</option>{options?.faculties.map((faculty) => <option key={faculty.id} value={faculty.id}>{faculty.name}</option>)}</select></label>
          <label className="report-field"><span>Ngành</span><select value={filters.majorId} onChange={(event) => setFilters((previous) => ({ ...previous, majorId: event.target.value }))}><option value="">Tất cả ngành</option>{visibleMajors.map((major) => <option key={major.id} value={major.id}>{major.name}</option>)}</select></label>
          <label className="report-field"><span>Khóa học</span><select value={filters.cohortId} onChange={(event) => setFilters((previous) => ({ ...previous, cohortId: event.target.value }))}><option value="">Tất cả khóa</option>{options?.cohorts.map((cohort) => <option key={cohort.id} value={cohort.id}>{cohort.code} ({cohort.admissionYear})</option>)}</select></label>
          <label className="report-field"><span>Năm tốt nghiệp</span><input type="number" min="1900" max="2200" value={filters.graduationYear} onChange={(event) => setFilters((previous) => ({ ...previous, graduationYear: event.target.value }))} placeholder="Tất cả năm" /></label>
          <label className="report-field"><span>Tình trạng việc làm</span><select value={filters.employmentStatus} onChange={(event) => setFilters((previous) => ({ ...previous, employmentStatus: event.target.value }))}><option value="">Tất cả tình trạng</option><option value="EMPLOYED">Đang làm việc</option><option value="SEEKING">Đang tìm việc</option><option value="STUDYING">Đang học tiếp</option><option value="OTHER">Khác</option><option value="UNKNOWN">UNKNOWN / chưa trả lời</option></select></label>
        </div>
        <div className="report-filter-actions"><button className="button button-primary" type="submit" disabled={loading}>{loading ? "Đang tổng hợp…" : "Xem thống kê"}<span>↗</span></button><button className="button button-secondary" type="button" disabled={loading} onClick={() => { setFilters(emptyFilters); void loadReport(""); }}>Xóa bộ lọc</button></div>
      </form>
      {error && <div className="inline-error report-error" role="alert">{error}</div>}
      {!report && !loading && !error && <section className="surface report-empty"><strong>Chưa có dữ liệu báo cáo</strong><p>Chọn bộ lọc rồi tải thống kê trong phạm vi tài khoản của bạn.</p></section>}
      {loading && !report && <section className="surface report-empty" role="status"><strong>Đang tổng hợp số liệu…</strong><p>Đang áp dụng bộ lọc và phạm vi quyền phía máy chủ.</p></section>}
      {report && <>
        <section className="report-meta-row"><div><span className="eyebrow">BÁO CÁO TỔNG HỢP</span><p>Tạo lúc {new Date(report.generatedAt).toLocaleString("vi-VN", { dateStyle: "medium", timeStyle: "short" })}</p></div><div className="report-downloads"><button type="button" className="button button-secondary" onClick={() => void exportReport("pdf")} disabled={loading || downloading !== null}>{downloading === "pdf" ? "Đang tạo PDF…" : "Tải PDF"}</button><button type="button" className="button button-primary" onClick={() => void exportReport("xlsx")} disabled={loading || downloading !== null}>{downloading === "xlsx" ? "Đang tạo Excel…" : "Tải Excel"}</button></div></section>
        <section className="report-kpi-grid" aria-label="Chỉ số hồ sơ">
          <Metric title="Tổng hồ sơ" value={report.totalProfiles} note="Hồ sơ đang hoạt động" tone="blue" />
          <Metric title="Đã xác minh" value={report.verifiedCount} note={ratio(report.verifiedCount, report.totalProfiles)} tone="green" />
          <Metric title="Chờ xác minh" value={report.pendingCount} note={ratio(report.pendingCount, report.totalProfiles)} tone="amber" />
          <Metric title="Độ bao phủ việc làm" value={report.responseCoverage === null ? "—" : `${report.responseCoverage}%`} note={`${report.reportedCount} có phản hồi · ${report.unknownCount} chưa trả lời`} tone="violet" />
        </section>
        <div className="report-detail-grid">
          <section className="surface report-breakdown"><div className="report-section-heading"><div><span className="eyebrow">UC8.1</span><h2>Tình trạng xác minh</h2></div><span className="report-icon">✓</span></div><div className="report-summary-row"><span>Đã xác minh</span><strong>{report.verifiedCount}</strong></div><div className="report-summary-row"><span>Chờ xác minh</span><strong>{report.pendingCount}</strong></div><div className="report-footnote">Các trạng thái khác, nếu có, không được gộp vào hai nhóm trên.</div></section>
          <section className="surface report-breakdown"><div className="report-section-heading"><div><span className="eyebrow">UC8.2</span><h2>Tình trạng việc làm</h2></div><span className="report-icon report-icon-green">↗</span></div><p className="report-source-note">Nguồn là thông tin do cựu sinh viên tự khai báo; tỷ lệ nhóm có phản hồi dùng {report.reportedCount} hồ sơ làm mẫu số.</p><div className="employment-bars">{report.employmentStatuses.map((item) => <div className="employment-bar-row" key={item.code}><div className="employment-bar-label"><span>{statusNames[item.code]}</span><strong>{item.count} <small>{item.percentage === null ? "—" : `${item.percentage}%`}</small></strong></div><div className="employment-track"><i style={{ width: `${Math.min(item.percentage ?? 0, 100)}%` }} /></div></div>)}</div><div className="report-footnote">UNKNOWN / chưa trả lời được tính riêng trên tổng hồ sơ; không có dữ liệu khi tổng bằng 0.</div></section>
        </div>
        <section className="surface report-applied"><div><span className="eyebrow">BỘ LỌC ĐÃ ÁP DỤNG</span><strong>{appliedNames.length ? appliedNames.join(" · ") : "Không áp dụng bộ lọc cụ thể"}</strong></div><span className="report-generated">Dữ liệu tổng hợp, không gồm email, điện thoại, CV hoặc mã sinh viên.</span></section>
      </>}
    </>}
  </>;
}

function Metric({ title, value, note, tone }: { title: string; value: string | number; note: string; tone: string }) {
  return <article className={`surface report-metric metric-${tone}`}><span>{title}</span><strong>{value}</strong><small>{note}</small></article>;
}

function ratio(value: number, total: number) {
  return total === 0 ? "Không có dữ liệu" : `${((value / total) * 100).toFixed(2)}%`;
}
