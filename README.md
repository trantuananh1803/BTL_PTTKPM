# Hệ thống quản lý cựu sinh viên

## Chuẩn bị trên máy tính mới

Gói chạy tự động trên **Windows x64**. Máy cần có Internet trong lần chạy đầu để tải các thành phần và thư viện còn thiếu. Người nhận cần tự cài **MySQL 8** và khởi động dịch vụ MySQL trước khi chạy ứng dụng; script không cài MySQL.

Các thành phần còn lại được chuẩn bị tự động bởi `setup-and-run.cmd`:

- Nếu chưa có JDK 17 trở lên, script tải JDK 17 dạng ZIP từ Eclipse Adoptium vào `%LOCALAPPDATA%\AlumniManagementSubmission\runtime`.
- Nếu chưa có Node.js đáp ứng yêu cầu Vite (20.19 trở lên, 22.12 trở lên hoặc phiên bản mới hơn), script tải Node.js 22 x64 dạng ZIP vào cùng thư mục.
- Các tệp runtime tải về được kiểm tra SHA-256 trước khi giải nén. Không cần quyền quản trị Windows vì runtime được lưu trong hồ sơ người dùng.
- Maven 3.10.0 đã có trong gói. Backend tự tải thư viện Maven; frontend tự chạy `npm ci` ở lần đầu.

Cache Maven được lưu tại `%LOCALAPPDATA%\AlumniManagementSubmission\maven-repository`. Tệp thông tin đăng nhập MySQL, nếu được lưu, được Windows DPAPI mã hóa cho tài khoản Windows hiện tại.

## Cài đặt MySQL và tạo schema demo

Trong MySQL Workbench, kết nối bằng tài khoản quản trị MySQL. Tạo schema riêng bằng câu lệnh:

```sql
CREATE DATABASE IF NOT EXISTS `alumni_management_submission_demo`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

Nếu chưa có tài khoản ứng dụng, thay `<MAT_KHAU_TU_DAT>` bằng mật khẩu do bạn tự chọn rồi chạy:

```sql
CREATE USER IF NOT EXISTS 'alumni_dev'@'localhost' IDENTIFIED BY '<MAT_KHAU_TU_DAT>';
GRANT ALL PRIVILEGES ON `alumni_management_submission_demo`.* TO 'alumni_dev'@'localhost';
```

Nếu `alumni_dev`@`localhost` đã tồn tại, bỏ câu `CREATE USER` và chỉ chạy câu `GRANT` bằng đúng tài khoản ứng dụng hiện có. Schema này dành riêng cho bản demo. Không trỏ ứng dụng vào database dùng chung hoặc có dữ liệu cần giữ lại vì Flyway sẽ tạo/cập nhật bảng khi backend khởi động.

## Khởi động ứng dụng bằng một lệnh

1. Giải nén ZIP vào thư mục người dùng có quyền ghi.
2. Mở PowerShell tại thư mục gốc vừa giải nén và chạy:

```powershell
.\setup-and-run.cmd
```

Lệnh này kiểm tra/cài JDK và Node.js nếu cần, kiểm tra MySQL tại `127.0.0.1:3306`, rồi mở cửa sổ PowerShell cho backend và frontend. Backend dùng Flyway trên schema demo; frontend tự cài thư viện npm ở lần đầu. Khi hai dịch vụ sẵn sàng, trình duyệt mở `http://127.0.0.1:5173`; API chạy tại `http://127.0.0.1:8080`.

Ở lần đầu backend chạy, nhập tài khoản MySQL có quyền trên schema demo. Thông tin đăng nhập được lưu bằng DPAPI cho tài khoản Windows hiện tại và không được đưa vào ZIP. Những lần chạy tiếp theo không hỏi lại. Nếu nhập sai và cần nhập lại, xóa tệp `%LOCALAPPDATA%\AlumniManagementSubmission\mysql-credential.xml`; thao tác này chỉ xóa bản lưu thông tin kết nối trên máy đó.

Nếu MySQL chạy ở cổng khác, đặt URL trước khi gọi lệnh trong cùng cửa sổ PowerShell, vẫn dùng schema demo:

```powershell
$env:ALUMNI_DB_URL = 'jdbc:mysql://127.0.0.1:3307/alumni_management_submission_demo?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Bangkok'
.\setup-and-run.cmd
```

Hai cửa sổ backend/frontend cần để mở trong lúc sử dụng. Để dừng dịch vụ, nhấn `Ctrl+C` trong từng cửa sổ.

## Tài khoản demo

Trong profile `dev`, trang đăng nhập có thể cung cấp gợi ý năm tài khoản demo: cựu sinh viên, sinh viên, nhà tuyển dụng, cán bộ quản lý và quản trị viên. Để tạo tài khoản demo, đặt `DEMO_ACCOUNTS_ENABLED=true` và cung cấp `DEMO_ACCOUNTS_PASSWORD` qua biến môi trường cục bộ; dự án không đặt mật khẩu mặc định. Không lưu giá trị này trong file được quản lý bằng Git. Dữ liệu tài khoản và hồ sơ được tạo bằng mã khởi tạo trong backend, không lấy từ danh sách sinh viên thật.

## Nhập danh sách cựu sinh viên bằng XLSX

File mẫu nằm tại `data/alumni-import-demo-20-cntt.xlsx`. File có 20 bản ghi dữ liệu giả, sáu cột theo thứ tự `student_code`, `full_name`, `faculty_code`, `major_code`, `cohort_code`, `graduation_year`. Backend tạo sẵn khoa `CNTT`, ngành `ICT1`–`ICT5`, khóa `K14`–`K16` khi khởi động profile demo.

Đăng nhập bằng tài khoản có quyền nhập Excel, mở mục **Xác minh & duyệt → Nhập Excel**, chọn file, xem trước rồi xác nhận. Mỗi mã sinh viên chỉ được nhập một lần vào một schema: nếu file đã được nhập trước đó, hệ thống báo mã trùng và không ghi đè hồ sơ hiện có. Dùng schema demo mới/rỗng nếu cần chạy lại kịch bản nhập từ đầu.

Endpoint nhập chỉ nhận `.xlsx`, tối đa 10 MB và 10.000 dòng. Tệp được xử lý trong vùng lưu riêng của backend; sau khi hoàn tất hoặc hết hạn preview, tệp tạm được xóa theo quy tắc của ứng dụng.

Để dừng ứng dụng, nhấn `Ctrl+C` trong từng cửa sổ PowerShell.
