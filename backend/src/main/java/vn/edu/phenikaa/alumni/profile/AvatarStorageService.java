package vn.edu.phenikaa.alumni.profile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.phenikaa.alumni.api.ApiException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Statement;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Service
public class AvatarStorageService {
    private static final long MAX_FILE_BYTES = 3L * 1024 * 1024;
    private static final int MAX_EDGE = 512;
    private static final long MAX_SOURCE_PIXELS = 50_000_000L;

    public record AvatarDownload(byte[] content, long fileId) {}
    private record StoredAvatar(String key, long id) {}

    private final JdbcTemplate jdbc;
    private final Path privateDirectory;

    public AvatarStorageService(JdbcTemplate jdbc,
                                @Value("${app.storage.private-directory:./var/private-files}") String privateDirectory) {
        this.jdbc = jdbc;
        this.privateDirectory = Path.of(privateDirectory).toAbsolutePath().normalize();
    }

    @Transactional
    public long replace(long accountId, MultipartFile upload) {
        if (upload == null || upload.isEmpty()) throw validation("Cần chọn ảnh đại diện.");
        if (upload.getSize() > MAX_FILE_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                "AVATAR_TOO_LARGE", "Ảnh đại diện không được vượt quá 3 MB.");
        requireAlumniProfile(accountId);

        byte[] normalized = normalizeImage(upload);
        String storageKey = UUID.randomUUID() + ".png";
        Path target = privateDirectory.resolve(storageKey).normalize();
        if (!target.startsWith(privateDirectory)) throw new IllegalStateException("Invalid private storage key");
        List<String> previousKeys = jdbc.query("""
                SELECT storage_key FROM private_files WHERE owner_account_id = ? AND purpose = 'AVATAR'
                """, (rs, rowNum) -> rs.getString(1), accountId);

        try {
            Files.createDirectories(privateDirectory);
            Path temporary = Files.createTempFile(privateDirectory, ".avatar-", ".tmp");
            try {
                Files.write(temporary, normalized);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PRIVATE_STORAGE_FAILURE",
                    "Không thể lưu ảnh đại diện vào vùng lưu trữ riêng tư.");
        }

        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO private_files (owner_account_id, purpose, storage_key, content_type, file_size)
                        VALUES (?, 'AVATAR', ?, 'image/png', ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, accountId);
                statement.setString(2, storageKey);
                statement.setLong(3, normalized.length);
                return statement;
            }, keyHolder);
            Number generated = keyHolder.getKey();
            if (generated == null) throw new IllegalStateException("Database did not return an avatar id");
            jdbc.update("DELETE FROM private_files WHERE owner_account_id = ? AND purpose = 'AVATAR' AND storage_key <> ?",
                    accountId, storageKey);
            jdbc.update("""
                    INSERT INTO audit_logs (actor_account_id, action_code, entity_type, entity_id, changed_fields)
                    VALUES (?, 'ALUMNI_AVATAR_REPLACED', 'PRIVATE_FILE', ?, JSON_ARRAY('avatar'))
                    """, accountId, generated.toString());
            registerFileCleanup(target, previousKeys, true);
            return generated.longValue();
        } catch (RuntimeException exception) {
            registerFileCleanup(target, List.of(), false);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public AvatarDownload getCurrent(long accountId) {
        requireAlumniProfile(accountId);
        List<StoredAvatar> rows = jdbc.query("""
                SELECT id, storage_key FROM private_files
                WHERE owner_account_id = ? AND purpose = 'AVATAR'
                ORDER BY id DESC LIMIT 1
                """, (rs, rowNum) -> new StoredAvatar(rs.getString("storage_key"), rs.getLong("id")), accountId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "AVATAR_NOT_FOUND",
                "Tài khoản chưa có ảnh đại diện.");
        Path file = privateDirectory.resolve(rows.get(0).key()).normalize();
        if (!file.startsWith(privateDirectory)) throw new IllegalStateException("Invalid private storage key");
        try {
            return new AvatarDownload(Files.readAllBytes(file), rows.get(0).id());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AVATAR_NOT_FOUND",
                    "Không tìm thấy ảnh đại diện trong vùng lưu trữ riêng tư.");
        }
    }

    private byte[] normalizeImage(MultipartFile upload) {
        try {
            byte[] bytes = upload.getBytes();
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                if (input == null) throw validation("Tệp không phải ảnh JPEG, PNG hoặc WebP hợp lệ.");
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw validation("Tệp không phải ảnh JPEG, PNG hoặc WebP hợp lệ.");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    if (!(format.equals("jpeg") || format.equals("jpg") || format.equals("png") || format.equals("webp")))
                        throw validation("Chỉ chấp nhận ảnh JPEG, PNG hoặc WebP.");
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > MAX_SOURCE_PIXELS)
                        throw validation("Kích thước ảnh nguồn không hợp lệ.");

                    ImageReadParam params = reader.getDefaultReadParam();
                    int subsampling = Math.max(1, (int) Math.ceil(Math.max(width, height) / 2048.0));
                    if (subsampling > 1) params.setSourceSubsampling(subsampling, subsampling, 0, 0);
                    BufferedImage source = reader.read(0, params);
                    if (source == null) throw validation("Không thể giải mã ảnh đã chọn.");
                    double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
                    int outputWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
                    int outputHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
                    BufferedImage output = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = output.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        graphics.drawImage(source, 0, 0, outputWidth, outputHeight, null);
                    } finally {
                        graphics.dispose();
                        source.flush();
                    }
                    try (ByteArrayOutputStream outputBytes = new ByteArrayOutputStream()) {
                        if (!ImageIO.write(output, "png", outputBytes))
                            throw new IllegalStateException("PNG writer is unavailable");
                        output.flush();
                        return outputBytes.toByteArray();
                    }
                } finally {
                    reader.dispose();
                }
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw validation("Không thể đọc ảnh. Hãy chọn tệp JPEG, PNG hoặc WebP hợp lệ.");
        }
    }

    private void requireAlumniProfile(long accountId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                JOIN alumni_profiles ap ON ap.user_id = ur.user_id
                WHERE ur.user_id = ? AND r.code = 'ALUMNI' AND ap.archived_at IS NULL
                """, Integer.class, accountId);
        if (count == null || count == 0) throw new ApiException(HttpStatus.FORBIDDEN,
                "ALUMNI_PROFILE_REQUIRED", "Chức năng này chỉ dành cho tài khoản cựu sinh viên có hồ sơ.");
    }

    private void registerFileCleanup(Path current, List<String> oldKeys, boolean removeOldAfterCommit) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    if (status != STATUS_COMMITTED) Files.deleteIfExists(current);
                    else if (removeOldAfterCommit) {
                        for (String oldKey : oldKeys) {
                            Path oldFile = privateDirectory.resolve(oldKey).normalize();
                            if (oldFile.startsWith(privateDirectory)) Files.deleteIfExists(oldFile);
                        }
                    }
                } catch (IOException ignored) {
                    // A cleanup failure leaves an orphaned private file, never a public file.
                }
            }
        });
    }

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AVATAR", message);
    }
}
