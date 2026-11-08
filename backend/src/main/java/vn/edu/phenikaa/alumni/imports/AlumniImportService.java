package vn.edu.phenikaa.alumni.imports;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.phenikaa.alumni.api.ApiException;
import vn.edu.phenikaa.alumni.security.AccountPrincipal;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@Service
public class AlumniImportService {
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_DATA_ROWS = 10_000;
    private static final List<String> HEADERS = List.of(
            "student_code", "full_name", "faculty_code", "major_code", "cohort_code", "graduation_year");
    private static final Pattern SAFE_FILENAME = Pattern.compile("[^A-Za-z0-9._-]");

    public record RowError(int rowNumber, String code, String message) {}
    public record PreviewRow(int rowNumber, String studentCode, String fullName, String facultyCode,
                             String majorCode, String cohortCode, Integer graduationYear) {}
    public record ImportBatchView(long id, String filename, String status, int totalRows, int validRows,
                                  int skippedRows, Instant expiresAt, List<PreviewRow> previewRows,
                                  List<RowError> errors) {}
    private record ParsedRow(int rowNumber, String studentCode, String fullName, String facultyCode,
                             String majorCode, String cohortCode, Integer graduationYear) {}
    private record Catalogs(Map<String, Long> faculties, Map<String, MajorRef> majors,
                            Map<String, Long> cohorts, Set<String> existingStudentCodes,
                            Set<Long> allowedFacultyIds) {}
    private record MajorRef(long id, long facultyId, boolean active) {}
    private record Validated(List<ParsedRow> valid, List<RowError> errors, int totalRows) {}
    private record BatchRow(long id, long createdBy, String filename, String hash, String storageKey,
                            String status, Instant expiresAt) {}

    private final JdbcTemplate jdbc;
    private final Path privateDirectory;
    private final TransactionTemplate transactionTemplate;

    public AlumniImportService(JdbcTemplate jdbc,
                              @Value("${app.storage.private-directory:./var/private-files}") String privateDirectory,
                              PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.privateDirectory = Path.of(privateDirectory).toAbsolutePath().normalize();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ZipSecureFile.setMinInflateRatio(0.02d);
        ZipSecureFile.setMaxEntrySize(MAX_FILE_BYTES * 4);
        ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
    }

    public byte[] template(AccountPrincipal actor) {
        authorizeImport(actor);
        try (Workbook workbook = new XSSFWorkbook(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Alumni");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) header.createCell(index).setCellValue(HEADERS.get(index));
            for (int index = 0; index < HEADERS.size(); index++) sheet.setColumnWidth(index, 24 * 256);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TEMPLATE_GENERATION_FAILED", "Không thể tạo tệp mẫu Excel.");
        }
    }

    public ImportBatchView preview(MultipartFile upload, AccountPrincipal actor) {
        authorizeImport(actor);
        cleanupExpired();
        validateUpload(upload);
        String originalName = safeFilename(upload.getOriginalFilename());
        String storageKey = UUID.randomUUID().toString();
        Path folder = importDirectory();
        Path staged = folder.resolve(storageKey + ".xlsx").normalize();
        if (!staged.startsWith(folder)) throw invalid("Tệp tải lên không hợp lệ.");
        try {
            Files.createDirectories(folder);
            upload.transferTo(staged);
            String hash = sha256(staged);
            List<ParsedRow> rows = parse(staged);
            if (rows.isEmpty()) throw invalid("Tệp không có dòng dữ liệu.");
            Catalogs catalogs = catalogs(actor, rows);
            Validated checked = validateRows(rows, catalogs);
            String status = checked.errors().isEmpty() ? "PREVIEW_READY" : "PREVIEW_WITH_ERRORS";
            return transactionTemplate.execute(transaction -> {
                KeyHolder keyHolder = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO alumni_import_batches
                                (created_by, source_filename, source_sha256, storage_key, status,
                                 total_rows, valid_rows, skipped_rows, expires_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6) + INTERVAL 24 HOUR)
                            """, Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, actor.id()); statement.setString(2, originalName);
                    statement.setString(3, hash); statement.setString(4, storageKey); statement.setString(5, status);
                    statement.setInt(6, checked.totalRows()); statement.setInt(7, checked.valid().size());
                    statement.setInt(8, checked.errors().size()); return statement;
                }, keyHolder);
                Number key = keyHolder.getKey();
                if (key == null) throw new IllegalStateException("Database did not return an import batch id");
                long batchId = key.longValue();
                for (RowError error : checked.errors()) {
                    jdbc.update("INSERT INTO alumni_import_row_errors (batch_id, source_row_number, error_code, safe_message) VALUES (?, ?, ?, ?)", batchId, error.rowNumber(), error.code(), error.message());
                }
                audit(actor.id(), "ALUMNI_IMPORT_PREVIEWED", batchId);
                return view(batchId, checked.valid());
            });
        } catch (ApiException exception) {
            deleteQuietly(staged);
            throw exception;
        } catch (Exception exception) {
            deleteQuietly(staged);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "IMPORT_PREVIEW_FAILED", "Không thể tạo bản xem trước nhập dữ liệu.");
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImportBatchView get(long id, AccountPrincipal actor) {
        authorizeImport(actor);
        BatchRow batch = getBatch(id, actor.id());
        List<PreviewRow> previewRows = List.of();
        if (batch.status().startsWith("PREVIEW")) {
            Path staged = stagedPath(batch.storageKey());
            if (Files.exists(staged)) {
                try {
                    List<ParsedRow> parsed = parse(staged);
                    Catalogs catalogs = catalogs(actor, parsed);
                    Validated checked = validateRows(parsed, catalogs);
                    previewRows = checked.valid().stream().limit(20).map(AlumniImportService::previewRow).toList();
                } catch (Exception ignored) {
                    previewRows = List.of();
                }
            }
        }
        return view(batch, previewRows, loadErrors(id));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImportBatchView commit(long id, AccountPrincipal actor) {
        authorizeImport(actor);
        BatchRow batch = getBatch(id, actor.id());
        if (!batch.status().startsWith("PREVIEW")) throw new ApiException(HttpStatus.CONFLICT, "IMPORT_NOT_COMMITTABLE", "Lô nhập không còn ở trạng thái xem trước.");
        if (!batch.expiresAt().isAfter(Instant.now())) {
            deleteQuietly(stagedPath(batch.storageKey()));
            jdbc.update("UPDATE alumni_import_batches SET status = 'EXPIRED' WHERE id = ?", id);
            throw new ApiException(HttpStatus.GONE, "IMPORT_PREVIEW_EXPIRED", "Bản xem trước đã hết hạn; vui lòng tải tệp lên lại.");
        }
        Path staged = stagedPath(batch.storageKey());
        if (!Files.exists(staged) || !batch.hash().equals(sha256(staged))) {
            deleteQuietly(staged);
            jdbc.update("UPDATE alumni_import_batches SET status = 'FAILED' WHERE id = ?", id);
            throw new ApiException(HttpStatus.CONFLICT, "IMPORT_SOURCE_UNAVAILABLE", "Tệp tạm không còn hợp lệ; vui lòng tạo bản xem trước mới.");
        }

        try {
            List<ParsedRow> parsed = parse(staged);
            Catalogs catalogs = catalogs(actor, parsed);
            Validated checked = validateRows(parsed, catalogs);
            transactionTemplate.execute(status -> {
                int created = 0;
                for (ParsedRow row : checked.valid()) {
                    jdbc.update("""
                            INSERT INTO alumni_profiles
                                (user_id, student_code, full_name, faculty_id, major_id, cohort_id, graduation_year,
                                 verification_status, directory_visibility)
                            VALUES (NULL, ?, ?, ?, ?, ?, ?, 'PENDING', 'PRIVATE')
                            """, row.studentCode(), row.fullName(), catalogs.faculties().get(row.facultyCode()),
                            row.majorCode().isBlank() ? null : catalogs.majors().get(row.majorCode()).id(),
                            catalogs.cohorts().get(row.cohortCode()), row.graduationYear());
                    created++;
                }
                jdbc.update("UPDATE alumni_import_batches SET status = 'COMMITTED', valid_rows = ?, skipped_rows = ?, committed_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND created_by = ?",
                        created, checked.errors().size(), id, actor.id());
                jdbc.update("DELETE FROM alumni_import_row_errors WHERE batch_id = ?", id);
                for (RowError error : checked.errors()) {
                    jdbc.update("INSERT INTO alumni_import_row_errors (batch_id, source_row_number, error_code, safe_message) VALUES (?, ?, ?, ?)",
                            id, error.rowNumber(), error.code(), error.message());
                }
                audit(actor.id(), "ALUMNI_IMPORT_COMMITTED", id);
                return created;
            });
            return view(id, List.of());
        } catch (RuntimeException exception) {
            jdbc.update("UPDATE alumni_import_batches SET status = 'FAILED' WHERE id = ? AND created_by = ?", id, actor.id());
            throw new ApiException(HttpStatus.CONFLICT, "IMPORT_COMMIT_ROLLED_BACK", "Không thể ghi trọn lô nhập; toàn bộ giao dịch đã được hoàn tác.");
        } finally {
            deleteQuietly(staged);
        }
    }

    private ImportBatchView view(long id, List<ParsedRow> validRows) {
        BatchRow batch = getBatch(id, null);
        return view(batch, validRows.stream().limit(20).map(AlumniImportService::previewRow).toList(), loadErrors(id));
    }

    private ImportBatchView view(BatchRow batch, List<PreviewRow> previewRows, List<RowError> errors) {
        Integer total = jdbc.queryForObject("SELECT total_rows FROM alumni_import_batches WHERE id = ?", Integer.class, batch.id());
        Integer valid = jdbc.queryForObject("SELECT valid_rows FROM alumni_import_batches WHERE id = ?", Integer.class, batch.id());
        Integer skipped = jdbc.queryForObject("SELECT skipped_rows FROM alumni_import_batches WHERE id = ?", Integer.class, batch.id());
        return new ImportBatchView(batch.id(), batch.filename(), batch.status(), n(total), n(valid), n(skipped),
                batch.expiresAt(), previewRows, errors);
    }

    private List<RowError> loadErrors(long id) {
        return jdbc.query("SELECT source_row_number, error_code, safe_message FROM alumni_import_row_errors WHERE batch_id = ? ORDER BY source_row_number LIMIT 1000",
                (rs, rowNum) -> new RowError(rs.getInt(1), rs.getString(2), rs.getString(3)), id);
    }

    private BatchRow getBatch(long id, Long ownerId) {
        String ownerClause = ownerId == null ? "" : " AND created_by = ?";
        Object[] params = ownerId == null ? new Object[]{id} : new Object[]{id, ownerId};
        List<BatchRow> rows = jdbc.query("SELECT id, created_by, source_filename, source_sha256, storage_key, status, expires_at FROM alumni_import_batches WHERE id = ?" + ownerClause,
                (rs, rowNum) -> new BatchRow(rs.getLong("id"), rs.getLong("created_by"), rs.getString("source_filename"),
                        rs.getString("source_sha256"), rs.getString("storage_key"), rs.getString("status"), rs.getTimestamp("expires_at").toInstant()), params);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "IMPORT_BATCH_NOT_FOUND", "Không tìm thấy lô nhập.");
        return rows.get(0);
    }

    private Catalogs catalogs(AccountPrincipal actor, List<ParsedRow> rows) {
        Map<String, Long> faculties = new HashMap<>();
        jdbc.query("SELECT id, code FROM faculties WHERE is_active = TRUE", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            faculties.put(code(rs.getString(2)), rs.getLong(1));
        });
        Map<String, MajorRef> majors = new HashMap<>();
        jdbc.query("SELECT id, faculty_id, code FROM majors WHERE is_active = TRUE", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            majors.put(code(rs.getString(3)), new MajorRef(rs.getLong(1), rs.getLong(2), true));
        });
        Map<String, Long> cohorts = new HashMap<>();
        jdbc.query("SELECT id, code FROM cohorts WHERE is_active = TRUE", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            cohorts.put(code(rs.getString(2)), rs.getLong(1));
        });
        Set<Long> allowed = actor.roles().contains("ADMIN") ? Set.copyOf(faculties.values()) : new HashSet<>(jdbc.queryForList("SELECT faculty_id FROM staff_faculty_scopes WHERE user_id = ?", Long.class, actor.id()));
        return new Catalogs(faculties, majors, cohorts, existingCodesPending(rows), allowed);
    }

    private Set<String> existingCodesPending(List<ParsedRow> rows) {
        List<String> codes = rows.stream().map(ParsedRow::studentCode).filter(value -> !value.isBlank()).distinct().toList();
        Set<String> found = new HashSet<>();
        for (int start = 0; start < codes.size(); start += 500) {
            List<String> part = codes.subList(start, Math.min(start + 500, codes.size()));
            String placeholders = String.join(",", Collections.nCopies(part.size(), "?"));
            found.addAll(jdbc.queryForList("SELECT UPPER(student_code) FROM alumni_profiles WHERE UPPER(student_code) IN (" + placeholders + ")", String.class, part.toArray()));
        }
        return found;
    }

    private Validated validateRows(List<ParsedRow> rows, Catalogs catalogs) {
        List<RowError> errors = new ArrayList<>();
        List<ParsedRow> valid = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (ParsedRow row : rows) if (!row.studentCode().isBlank()) occurrences.merge(row.studentCode(), 1, Integer::sum);
        for (ParsedRow row : rows) {
            String error = null;
            String errorCode = "ROW_INVALID";
            if (row.studentCode().isBlank() || row.fullName().isBlank()) error = "Thiếu mã sinh viên hoặc họ tên.";
            else if (row.studentCode().length() > 32 || row.fullName().length() > 180) error = "Mã sinh viên hoặc họ tên vượt giới hạn độ dài.";
            else if (row.facultyCode().isBlank() || row.cohortCode().isBlank() || row.graduationYear() == null) error = "Thiếu khoa, khóa hoặc năm tốt nghiệp.";
            else if (row.graduationYear() < 1900 || row.graduationYear() > 2200) error = "Năm tốt nghiệp phải trong khoảng 1900–2200.";
            else if (!catalogs.faculties().containsKey(row.facultyCode()) || !catalogs.cohorts().containsKey(row.cohortCode())) error = "Mã khoa hoặc khóa không tồn tại/đã ngừng.";
            else if (!catalogs.allowedFacultyIds().contains(catalogs.faculties().get(row.facultyCode()))) { error = "Khoa nằm ngoài phạm vi được phân công."; errorCode = "FACULTY_OUT_OF_SCOPE"; }
            else if (!row.majorCode().isBlank() && (!catalogs.majors().containsKey(row.majorCode())
                    || catalogs.majors().get(row.majorCode()).facultyId() != catalogs.faculties().get(row.facultyCode()))) error = "Mã ngành không tồn tại, đã ngừng hoặc không thuộc khoa.";
            else if (occurrences.getOrDefault(row.studentCode(), 0) > 1 || catalogs.existingStudentCodes().contains(row.studentCode())) { error = "Mã sinh viên bị trùng trong tệp hoặc hệ thống."; errorCode = "DUPLICATE_STUDENT_CODE"; }
            if (error == null) valid.add(row); else errors.add(new RowError(row.rowNumber(), errorCode, error));
        }
        return new Validated(List.copyOf(valid), List.copyOf(errors), rows.size());
    }

    private List<ParsedRow> parse(Path file) {
        try (InputStream input = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() < 1) throw invalid("Tệp không có trang tính.");
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null || header.getLastCellNum() != HEADERS.size()) throw invalid("Tệp phải có đúng sáu cột theo mẫu.");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (int col = 0; col < HEADERS.size(); col++) {
                if (!HEADERS.get(col).equals(formatCell(header.getCell(col), formatter))) throw invalid("Tên hoặc thứ tự cột không đúng mẫu.");
            }
            int dataRows = 0;
            List<ParsedRow> result = new ArrayList<>();
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlank(row, formatter)) continue;
                if (++dataRows > MAX_DATA_ROWS) throw invalid("Tệp vượt quá 10.000 dòng dữ liệu.");
                if (row.getLastCellNum() > HEADERS.size()) {
                    result.add(new ParsedRow(rowIndex + 1, "", "", "", "", "", null));
                    continue;
                }
                String[] values = new String[HEADERS.size()];
                boolean formula = false;
                for (int col = 0; col < HEADERS.size(); col++) {
                    Cell cell = row.getCell(col);
                    if (cell != null && cell.getCellType() == CellType.FORMULA) formula = true;
                    values[col] = formatCell(cell, formatter).trim();
                }
                Integer graduationYear = null;
                if (!formula && !values[5].isBlank()) {
                    try { graduationYear = Integer.valueOf(values[5]); }
                    catch (NumberFormatException ignored) { }
                }
                result.add(new ParsedRow(rowIndex + 1, code(values[0]), values[1], code(values[2]),
                        code(values[3]), code(values[4]), formula ? null : graduationYear));
            }
            return result;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("Không thể đọc tệp XLSX.");
        }
    }

    private static String formatCell(Cell cell, DataFormatter formatter) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }
    private static boolean isBlank(Row row, DataFormatter formatter) {
        for (int col = 0; col < HEADERS.size(); col++) if (!formatCell(row.getCell(col), formatter).isBlank()) return false;
        return true;
    }
    private static PreviewRow previewRow(ParsedRow row) {
        return new PreviewRow(row.rowNumber(), row.studentCode(), row.fullName(), row.facultyCode(), row.majorCode(), row.cohortCode(), row.graduationYear());
    }

    private void cleanupExpired() {
        List<String> keys = jdbc.queryForList("SELECT storage_key FROM alumni_import_batches WHERE status LIKE 'PREVIEW%' AND expires_at <= CURRENT_TIMESTAMP(6)", String.class);
        for (String key : keys) deleteQuietly(stagedPath(key));
        jdbc.update("UPDATE alumni_import_batches SET status = 'EXPIRED' WHERE status LIKE 'PREVIEW%' AND expires_at <= CURRENT_TIMESTAMP(6)");
    }
    private void authorizeImport(AccountPrincipal actor) {
        if (!actor.roles().contains("ADMIN") && !(actor.roles().contains("FACULTY_STAFF") && actor.permissions().contains("ALUMNI_IMPORT")))
            throw new ApiException(HttpStatus.FORBIDDEN, "IMPORT_NOT_ALLOWED", "Bạn không được cấp quyền nhập hồ sơ cựu sinh viên.");
    }
    private void validateUpload(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) throw invalid("Cần chọn tệp XLSX.");
        if (upload.getSize() > MAX_FILE_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Tệp vượt quá giới hạn 10 MB.");
        String filename = upload.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw invalid("Chỉ nhận tệp .xlsx; không hỗ trợ .xls.");
        try (InputStream input = upload.getInputStream()) {
            byte[] signature = input.readNBytes(4);
            if (signature.length < 4 || signature[0] != 'P' || signature[1] != 'K') throw invalid("Nội dung tệp không phải Excel XLSX hợp lệ.");
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw invalid("Không đọc được tệp tải lên."); }
    }
    private Path importDirectory() { return privateDirectory.resolve("import-staging").normalize(); }
    private Path stagedPath(String key) {
        if (key == null || !key.matches("[0-9a-fA-F-]{36}")) throw invalid("Mã tệp tạm không hợp lệ.");
        Path folder = importDirectory();
        Path path = folder.resolve(key + ".xlsx").normalize();
        if (!path.startsWith(folder)) throw invalid("Đường dẫn tệp tạm không hợp lệ.");
        return path;
    }
    private static String safeFilename(String name) {
        String base = name == null ? "alumni.xlsx" : name.replace('\\', '/').substring(name.replace('\\', '/').lastIndexOf('/') + 1);
        String safe = SAFE_FILENAME.matcher(base).replaceAll("_");
        return safe.length() > 255 ? safe.substring(safe.length() - 255) : safe;
    }
    private static String sha256(Path file) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
        catch (Exception exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_HASH_FAILED", "Không thể xác thực tệp tạm."); }
    }
    private void audit(long actorId, String action, long batchId) {
        jdbc.update("INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id) VALUES (?, ?, 'ALUMNI_IMPORT_BATCH', ?)", actorId, action, Long.toString(batchId));
    }
    private static String code(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static int n(Integer value) { return value == null ? 0 : value; }
    private static void deleteQuietly(Path path) { try { Files.deleteIfExists(path); } catch (Exception ignored) { } }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "XLSX_INVALID", message); }
}
