# Kiến Trúc Kỹ Thuật & Độ Tin Cậy - AutoGreeting

Dự án được xây dựng với mục tiêu giải quyết triệt để vấn đề "mất tín hiệu khởi động" trên các dòng Head Unit Android ô tô.

## 1. Thành Phần Cốt Lõi: AppRuntimePolicies

Đây là trung tâm điều khiển toàn bộ hành vi của ứng dụng, thay thế cho các logic đơn giản của MacroDroid.

*   **Window-based Logic:** Sử dụng các hằng số thời gian (`BOOT_STARTUP_WINDOW`, `HEAD_UNIT_SLEEP_WAKE_WINDOW`) để định nghĩa trạng thái của thiết bị.
*   **Startup Recovery:** Hệ thống đánh giá `BootLikeVisibleCompatEvaluation` giúp quyết định xem một lần mở app có phải là kết quả của việc hệ thống "quên" chạy ngầm hay không.
*   **Device-Specific Workarounds:**
    *   **Compose Hover Fix:** Xử lý lỗi crash `ACTION_HOVER_EXIT` trên các thiết bị Android 11/SDK 30 (như Mi A3 hoặc Head Unit dùng chip tương đương).
    *   **Overlay Compatibility:** Cơ chế `canDrawOverlaysCompat` hỗ trợ kiểm tra quyền hiển thị trên nhiều phiên bản Android khác nhau, đảm bảo Floating Button luôn hoạt động.

## 2. Hệ Thống Chẩn Đoán (Diagnostics Engine)

Ứng dụng tích hợp một công cụ render báo cáo lỗi cực kỳ chi tiết (`DiagnosticsReportRenderer`), giúp người dùng và kỹ thuật viên tìm ra nguyên nhân tại sao lời chào không phát:

*   **Timeline Analysis:** Ghi lại chính xác thời điểm nhận tín hiệu Boot, thời điểm Service khởi chạy và kết quả yêu cầu Audio Focus.
*   **Signal Probe:** Theo dõi các "Raw Boot Receiver Probe" để xác định xem lỗi nằm ở hệ thống Android (không gửi tín hiệu) hay ở ứng dụng (bị chặn xử lý).
*   **Privacy Sanitizer:** Tự động lọc các thông tin nhạy cảm (Số điện thoại, IP, Email) trước khi xuất báo cáo, đảm bảo an toàn thông tin người dùng.

## 3. Quản Lý Âm Thanh & Dữ Liệu

*   **Multi-Engine Audio:** Hỗ trợ linh hoạt giữa MediaPlayer (nhẹ, tương thích cao) và ExoPlayer (mạnh mẽ cho các định dạng phức tạp).
*   **Smart Cache:** Cơ chế `isSoundMissing` kiểm tra kích thước và sự tồn tại của file âm thanh (tối thiểu 2KB). Nếu file không hợp lệ, hệ thống sẽ tự động khôi phục từ máy chủ.
*   **DataStore Preferences:** Sử dụng Jetpack DataStore thay cho SharedPreferences để đảm bảo an toàn dữ liệu và tránh tình trạng "corrupt" dữ liệu khi Head Unit bị ngắt điện đột ngột.
