# 🛡️ V-Shield Home - P-Innovation 2026

**V-Shield Home** là ứng dụng bảo mật di động được thiết kế để bảo vệ người dùng khỏi các mối đe dọa trực tuyến. Dự án này được phát triển như một phần của cuộc thi **P-Innovation 2026** bởi nhóm sinh viên chuyên ngành **An toàn thông tin**.

---

## 🌟 Tổng quan dự án
Ứng dụng hoạt động dựa trên cơ chế **Local VPN** để giám sát và lọc lưu lượng truy cập ngay trên thiết bị. Mục tiêu chính là ngăn chặn việc kết nối tới các tên miền (domain) chứa mã độc, lừa đảo mà không cần gửi dữ liệu ra máy chủ bên ngoài, giúp đảm bảo quyền riêng tư tuyệt đối cho người dùng.

## ✨ Tính năng nổi bật
- **Chặn tên miền độc hại:** Tự động phát hiện và ngăn chặn truy cập các domain nằm trong danh sách đen (blocklist).
- **Cơ chế Local VPN:** Sử dụng `VpnService` của Android để xử lý gói tin tại chỗ, không gây trễ mạng.
- **Quản lý danh sách tùy chỉnh:** Người dùng có thể cập nhật hoặc thêm các nguồn blocklist khác nhau.
- **Tiết kiệm tài nguyên:** Tối ưu hóa để chạy ngầm ổn định trên các thiết bị Android mà không gây hao pin.

## 💻 Công nghệ sử dụng
- **Ngôn ngữ lập trình:** Java (Android SDK).
- **Môi trường phát triển:** Android Studio.
- **Hệ thống quản lý:** Git & GitHub.
- **Kiến trúc:** Local VPN Service.

## 📂 Cấu trúc thư mục chính
- `app/src/main/java/`: Mã nguồn xử lý logic VPN và quản trị hệ thống.
- `app/src/main/assets/`: Chứa file `blocklist_sample.txt` định nghĩa các domain cần chặn.
- `app/src/main/res/`: Tài nguyên về giao diện và hình ảnh của ứng dụng.

## 🚀 Hướng dẫn cài đặt
Để chạy dự án trên máy tính cá nhân (ví dụ: ThinkBook 16 G7+), hãy thực hiện các bước sau:

1. **Clone project:**
   ```bash
   git clone [https://github.com/quangchi306/vshield-pinnovation.git](https://github.com/quangchi306/vshield-pinnovation.git)
