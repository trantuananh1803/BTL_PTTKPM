# Hệ thống quản lý cựu sinh viên

## Chuẩn bị trên máy mới

Cài JDK 17 trở lên, MySQL 8, Node.js 20.19 trở lên (hoặc Node.js 22.12 trở lên). Maven 3.10.0 đã được đặt trong gói. Máy cần Internet ở lần đầu chạy để tải các thư viện Maven và npm. Cache Maven được lưu trong `%LOCALAPPDATA%\AlumniManagementSubmission\maven-repository`.

Trong MySQL Workbench, kết nối bằng tài khoản quản trị MySQL. Tạo schema riêng bằng câu lệnh thứ nhất:

```sql
CREATE DATABASE IF NOT EXISTS `alumni_management_submission_demo`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

Trên máy chưa có tài khoản ứng dụng, thay `<MAT_KHAU_TU_DAT>` bằng mật khẩu do bạn tự chọn rồi chạy hai câu lệnh dưới đây. Nếu `alumni_dev`@`localhost` đã tồn tại, bỏ câu `CREATE USER` và chỉ chạy câu `GRANT` bằng đúng tài khoản ứng dụng hiện có.

```sql
CREATE USER IF NOT EXISTS 'alumni_dev'@'localhost' IDENTIFIED BY '<MAT_KHAU_TU_DAT>';
GRANT ALL PRIVILEGES ON `alumni_management_submission_demo`.* TO 'alumni_dev'@'localhost';
```

Nhập cùng tên tài khoản và mật khẩu đó khi script backend hỏi lần đầu. Schema trên dành riêng cho bản demo này. Không đổi URL kết nối sang database dùng chung hoặc có dữ liệu cần giữ lại vì Flyway sẽ tạo/cập nhật bảng khi backend khởi động.

## Khởi động bằng hai lệnh

Giải nén gói. Mở hai cửa sổ PowerShell tại thư mục gốc vừa giải nén.

Ở cửa sổ thứ nhất, chạy backend:

```powershell
.\run-backend.cmd
```

Lần đầu chạy, nhập tên và mật khẩu MySQL có quyền trên schema demo. Gói lưu thông tin đăng nhập bằng Windows DPAPI cho tài khoản Windows hiện tại; những lần chạy sau không hỏi lại. Backend chạy Flyway và tạo các tài khoản demo cùng danh mục CNTT khi khởi động.

Ở cửa sổ thứ hai, chạy frontend:

```powershell
.\run-frontend.cmd
```

Lần đầu, frontend tự chạy `npm ci` để cài thư viện. Mở `http://localhost:5173`; API chạy tại `http://localhost:8080`.

Để đổi máy chủ/cổng MySQL nhưng vẫn dùng đúng schema demo, đặt URL trong cửa sổ backend trước khi chạy:

```powershell
$env:ALUMNI_DB_URL = 'jdbc:mysql://127.0.0.1:3307/alumni_management_submission_demo?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Bangkok'
```

## Tài khoản demo

Trang đăng nhập cung cấp gợi ý năm tài khoản demo: cựu sinh viên, sinh viên, nhà tuyển dụng, cán bộ quản lý và quản trị viên. Mật khẩu demo được lấy từ cấu hình `DEMO_ACCOUNTS_PASSWORD` trong `backend/src/main/resources/application.yml`; chỉ dùng trong môi trường demo cục bộ. Dữ liệu tài khoản và hồ sơ được tạo bằng mã khởi tạo trong backend, không lấy từ danh sách sinh viên thật.

## Nhập danh sách cựu sinh viên bằng XLSX

File mẫu nằm tại `data/alumni-import-demo-20-cntt.xlsx`. File có 20 bản ghi dữ liệu giả, sáu cột theo thứ tự `student_code`, `full_name`, `faculty_code`, `major_code`, `cohort_code`, `graduation_year`. Backend tạo sẵn khoa `CNTT`, ngành `ICT1`–`ICT5`, khóa `K14`–`K16` khi khởi động profile demo.

Đăng nhập bằng tài khoản có quyền nhập Excel, mở mục **Xác minh & duyệt → Nhập Excel**, chọn file, xem trước rồi xác nhận. Mỗi mã sinh viên chỉ được nhập một lần vào một schema: nếu file đã được nhập trước đó, hệ thống báo mã trùng và không ghi đè hồ sơ hiện có. Dùng schema demo mới/rỗng nếu cần chạy lại kịch bản nhập từ đầu.

Endpoint nhập chỉ nhận `.xlsx`, tối đa 10 MB và 10.000 dòng. Tệp được xử lý trong vùng lưu riêng của backend; sau khi hoàn tất hoặc hết hạn preview, tệp tạm được xóa theo quy tắc của ứng dụng.

Để dừng ứng dụng, nhấn `Ctrl+C` trong từng cửa sổ PowerShell.