# Tối Ưu Hóa Thiết Bị - AutoGreeting

Để ứng dụng AutoGreeting có thể tự khởi động ngay khi xe mở máy, cần thực hiện các thiết lập tối ưu hóa sau đây.

## 1. Cấp Quyền Đặc Biệt
- **Xuất hiện trên cùng (Overlay):** Cần thiết để hiển thị nút điều khiển nổi.
- **Quyền Tự khởi chạy (Autostart):** Đây là quyền quan trọng nhất. Nếu không có quyền này, `BootCompletedReceiver` sẽ bị hệ thống chặn lại.
- **Tắt tối ưu hóa pin:** Đảm bảo hệ thống không "giết" ứng dụng khi bạn đang lái xe hành trình dài.

## 2. Thiết Lập Cho Các Dòng Máy Head Unit (Màn hình Android xe hơi)
- **Xiaomi / Redmi:**
    - Vào Cài đặt -> Ứng dụng -> Quản lý ứng dụng -> AutoGreeting.
    - Bật "Tự khởi động".
    - Tiết kiệm pin: Chọn "Không hạn chế".
- **Oppo / Realme / Vivo:**
    - Bật "Cho phép khởi động tự động".
    - Cho phép "Chạy dưới nền không hạn chế".
- **Các dòng màn hình Android chuyên dụng (Android Head Unit):**
    - Kiểm tra trong phần cài đặt của Launcher xe hơi (Car Launcher) xem có mục "Auto-run apps" không và thêm AutoGreeting vào danh sách.

## 3. Khắc Phục Sự Cố Khởi Động
Nếu bạn không nghe thấy âm thanh chào mừng khi khởi động:
1. **Kiểm tra âm lượng:** Đảm bảo âm lượng Media của hệ thống không ở mức 0.
2. **Kiểm tra quyền Boot:** Sử dụng ứng dụng kiểm tra Receiver hoặc xem log trong phần "Diagnostics" của app để biết `BootCompletedReceiver` có được kích hoạt không.
3. **Độ trễ hệ thống:** Một số đầu Android cần thời gian nạp driver âm thanh. Bạn có thể điều chỉnh độ trễ khởi động trong phần cài đặt nâng cao của app (nếu có).

## 4. Bảo Trì & Cập Nhật
- Sử dụng `UpdateManager` tích hợp sẵn trong ứng dụng để kiểm tra các phiên bản mới nhất từ máy chủ, đảm bảo tính tương thích với các bản cập nhật Android mới của hãng xe.
