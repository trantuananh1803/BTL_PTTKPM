package vn.edu.phenikaa.alumni.reports;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.api.ReportDtos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReportExportService {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'ICT'")
            .withZone(ZoneId.of("Asia/Bangkok"));
    private final String pdfFontPath;

    public ReportExportService(@Value("${app.reports.pdf-font-path:}") String pdfFontPath) {
        this.pdfFontPath = pdfFontPath == null ? "" : pdfFontPath.trim();
    }

    public byte[] xlsx(String reportKind, ReportDtos.Summary report) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Báo cáo tổng hợp");
            CellStyle heading = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headingFont = workbook.createFont();
            headingFont.setBold(true);
            headingFont.setColor(IndexedColors.WHITE.getIndex());
            heading.setFont(headingFont);
            heading.setFillForegroundColor(IndexedColors.INDIGO.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            int rowIndex = 0;
            Row title = sheet.createRow(rowIndex++);
            title.createCell(0).setCellValue("BÁO CÁO " + ("employment".equals(reportKind) ? "TÌNH TRẠNG VIỆC LÀM" : "HỒ SƠ CỰU SINH VIÊN"));
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 2));
            Row generated = sheet.createRow(rowIndex++);
            generated.createCell(0).setCellValue("Thời điểm tạo");
            generated.createCell(1).setCellValue(DATE_FORMAT.format(report.generatedAt()));
            ReportDtos.AppliedFilters filters = report.appliedFilters();
            Row filterRow = sheet.createRow(rowIndex++);
            filterRow.createCell(0).setCellValue("Bộ lọc");
            filterRow.createCell(1).setCellValue(filterDescription(filters));
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(2, 2, 1, 2));

            rowIndex++;
            String[][] metrics = {
                    {"Chỉ số", "Số lượng", "Tỷ lệ"},
                    {"Tổng hồ sơ", Long.toString(report.totalProfiles()), ratio(report.totalProfiles(), report.totalProfiles())},
                    {"Đã xác minh", Long.toString(report.verifiedCount()), ratio(report.verifiedCount(), report.totalProfiles())},
                    {"Chờ xác minh", Long.toString(report.pendingCount()), ratio(report.pendingCount(), report.totalProfiles())},
                    {"Độ bao phủ phản hồi việc làm", Long.toString(report.reportedCount()), ratio(report.reportedCount(), report.totalProfiles())},
                    {"UNKNOWN / chưa trả lời", Long.toString(report.unknownCount()), ratio(report.unknownCount(), report.totalProfiles())}
            };
            for (int i = 0; i < metrics.length; i++) {
                Row row = sheet.createRow(rowIndex++);
                for (int col = 0; col < metrics[i].length; col++) {
                    row.createCell(col).setCellValue(metrics[i][col]);
                    if (i == 0) row.getCell(col).setCellStyle(heading);
                }
            }
            rowIndex++;
            Row statusTitle = sheet.createRow(rowIndex++);
            statusTitle.createCell(0).setCellValue("Tình trạng việc làm (nguồn tự khai báo)");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIndex - 1, rowIndex - 1, 0, 2));
            String[][] statusRows = {{"Mã", "Số lượng", "Tỷ lệ"}};
            Row statusHeader = sheet.createRow(rowIndex++);
            for (int col = 0; col < 3; col++) {
                statusHeader.createCell(col).setCellValue(statusRows[0][col]);
                statusHeader.getCell(col).setCellStyle(heading);
            }
            for (ReportDtos.EmploymentStatusMetric metric : report.employmentStatuses()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(statusLabel(metric.code()));
                row.createCell(1).setCellValue(metric.count());
                row.createCell(2).setCellValue(percent(metric.percentage()));
            }
            Row note = sheet.createRow(rowIndex + 1);
            note.createCell(0).setCellValue("Dữ liệu việc làm do người dùng tự khai báo; báo cáo chỉ chứa số liệu tổng hợp.");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIndex + 1, rowIndex + 1, 0, 2));
            for (int col = 0; col < 3; col++) sheet.autoSizeColumn(col);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_EXPORT_FAILED", "Không thể tạo tệp Excel báo cáo.");
        }
    }

    public byte[] pdf(String reportKind, ReportDtos.Summary report) {
        File fontFile = pdfFontPath.isBlank() ? null : new File(pdfFontPath);
        if (fontFile == null || !fontFile.isFile())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REPORT_PDF_FONT_NOT_CONFIGURED",
                    "Chưa cấu hình phông chữ Unicode cho báo cáo PDF.");
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDFont font = PDType0Font.load(document, fontFile);
            List<String> lines = wrapPdfLines(pdfLines(reportKind, report), 88);
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            float margin = 48;
            float y = page.getMediaBox().getHeight() - margin;
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    PDFont selected = font;
                    float size = index == 0 ? 16 : 10;
                    stream.beginText();
                    stream.setFont(selected, size);
                    stream.newLineAtOffset(margin, y);
                    stream.showText(line);
                    stream.endText();
                    y -= index == 0 ? 30 : 20;
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_EXPORT_FAILED", "Không thể tạo tệp PDF báo cáo.");
        }
    }

    private static List<String> pdfLines(String kind, ReportDtos.Summary r) {
        List<String> lines = new ArrayList<>();
        lines.add("BÁO CÁO " + ("employment".equals(kind) ? "TÌNH TRẠNG VIỆC LÀM" : "HỒ SƠ CỰU SINH VIÊN"));
        lines.add("Thời điểm tạo: " + DATE_FORMAT.format(r.generatedAt()));
        lines.add("Bộ lọc: " + filterDescription(r.appliedFilters()));
        lines.add("Tổng hồ sơ: " + r.totalProfiles());
        lines.add("Đã xác minh: " + r.verifiedCount() + " (" + ratio(r.verifiedCount(), r.totalProfiles()) + ")");
        lines.add("Chờ xác minh: " + r.pendingCount() + " (" + ratio(r.pendingCount(), r.totalProfiles()) + ")");
        lines.add("Có phản hồi việc làm: " + r.reportedCount() + "; độ bao phủ " + percent(r.responseCoverage()));
        lines.add("UNKNOWN / chưa trả lời: " + r.unknownCount() + " (" + ratio(r.unknownCount(), r.totalProfiles()) + ")");
        lines.add("Tình trạng việc làm (nguồn tự khai báo):");
        for (ReportDtos.EmploymentStatusMetric metric : r.employmentStatuses())
            lines.add("- " + statusLabel(metric.code()) + ": " + metric.count() + " (" + percent(metric.percentage()) + ")");
        lines.add("Số liệu là dữ liệu tổng hợp; tình trạng việc làm do người dùng tự khai báo.");
        return lines;
    }

    private static List<String> wrapPdfLines(List<String> source, int maxCharacters) {
        List<String> wrapped = new ArrayList<>();
        for (String line : source) {
            String remaining = line;
            while (remaining.length() > maxCharacters) {
                int split = remaining.lastIndexOf(' ', maxCharacters);
                if (split < 1) split = maxCharacters;
                wrapped.add(remaining.substring(0, split));
                remaining = remaining.substring(split).stripLeading();
            }
            wrapped.add(remaining);
        }
        return wrapped;
    }

    private static String filterDescription(ReportDtos.AppliedFilters f) {
        List<String> values = new ArrayList<>();
        if (f.facultyId() != null) values.add("facultyId=" + f.facultyId());
        if (f.majorId() != null) values.add("majorId=" + f.majorId());
        if (f.cohortId() != null) values.add("cohortId=" + f.cohortId());
        if (f.graduationYear() != null) values.add("graduationYear=" + f.graduationYear());
        if (f.employmentStatus() != null) values.add("employmentStatus=" + f.employmentStatus());
        return values.isEmpty() ? "Không áp dụng" : String.join(", ", values);
    }

    private static String ratio(long numerator, long denominator) {
        if (denominator == 0) return "Không có dữ liệu";
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, java.math.RoundingMode.HALF_UP) + "%";
    }

    private static String percent(BigDecimal value) { return value == null ? "Không có dữ liệu" : value + "%"; }
    private static String statusLabel(String code) {
        return switch (code) {
            case "EMPLOYED" -> "Đang làm việc";
            case "SEEKING" -> "Đang tìm việc";
            case "STUDYING" -> "Đang học tiếp";
            case "OTHER" -> "Khác";
            default -> "UNKNOWN / chưa trả lời";
        };
    }
}
