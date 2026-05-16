# Tối Ưu Hóa Thiết Bị Xe Hơi - AutoGreeting

Màn hình Android ô tô (Head Unit) có cơ chế quản lý năng lượng rất khác biệt so với điện thoại. Tài liệu này hướng dẫn cách thiết lập để AutoGreeting hoạt động ổn định 100%.

## 1. Vượt Qua Chế Độ "Sleep" Của Xe

Hầu hết các xe hiện đại không tắt máy hoàn toàn mà vào chế độ **Suspend to RAM**. 

*   **Vấn đề:** Khi xe tỉnh dậy, nó không gửi tín hiệu `BOOT_COMPLETED`.
*   **Giải pháp:** AutoGreeting sử dụng quyền **Tự khởi động (Autostart)** để duy trì một bộ lắng nghe trạng thái hệ thống. Hãy đảm bảo bạn đã bật "Allow Autostart" trong cài đặt ứng dụng.

## 2. Quyền Hiển Thị Trên Ứng Dụng Khác (Overlay)

Quyền này không chỉ dành cho Floating Button mà còn giúp ứng dụng có thể "nổi lên" để thực hiện cơ chế **Visible Recovery** (Phát bù lời chào) nếu hệ thống chặn việc phát nhạc ngầm.

*   Vào **Cài đặt hệ thống** -> **Ứng dụng** -> **Quyền đặc biệt** -> **Xuất hiện trên cùng**.
*   Tìm và bật **AutoGreeting**.

## 3. Thiết Lập Cho Các Dòng Máy Đặc Thù

*   **Màn hình dùng chip Qualcomm/Rockchip (Android 10+):**
    *   Thường có trình quản lý pin cực kỳ nghiêm ngặt. Cần chọn "Không tối ưu hóa" cho AutoGreeting.
    *   Nếu có mục "White list" trong cài đặt của nhà sản xuất (Car Settings), hãy thêm app vào đó.
*   **Thiết bị Xiaomi/Redmi (Dùng làm màn hình phụ):**
    *   Phải bật quyền "Hiển thị cửa sổ pop-up khi chạy nền". Nếu không, cơ chế chẩn đoán và lời chào sẽ không thể kích hoạt.
*   **Khắc phục lỗi Crash trên Android 11:**
    *   Nếu thiết bị có kết nối chuột hoặc bàn điều khiển cảm ứng (trackpad), ứng dụng đã tích hợp sẵn logic chống lỗi `HOVER_EXIT`. Bạn không cần cấu hình gì thêm.

## 4. Kiểm Tra Với Diagnostics

Nếu lời chào không phát, hãy sử dụng tính năng **"Xuất báo cáo chẩn đoán"** trong ứng dụng. Báo cáo này sẽ chỉ rõ:
1.  Thiết bị có gửi tín hiệu Boot không?
2.  Quyền Overlay đã thực sự được hệ thống cấp chưa?
3.  Có ứng dụng nào khác (như trình phát nhạc mặc định của xe) đang chặn Audio Focus không?
