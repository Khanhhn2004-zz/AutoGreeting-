# Luồng Vận Hành Hệ Thống - AutoGreeting

AutoGreeting không chỉ dựa vào tín hiệu Boot tiêu chuẩn mà sử dụng một hệ thống đa tầng để đảm bảo lời chào luôn được phát, ngay cả khi hệ thống Android của xe (Head Unit) gặp lỗi hoặc bị hạn chế.

## 1. Các Tầng Kích Hoạt (Activation Tiers)

Ứng dụng lắng nghe và xử lý khởi động qua 3 kịch bản chính:

*   **Tầng 1: Tín hiệu hệ thống trực tiếp (Standard Boot)**
    *   Lắng nghe `BOOT_COMPLETED` và `LOCKED_BOOT_COMPLETED`.
    *   Hỗ trợ các tín hiệu đặc thù của phần cứng: `QUICKBOOT_POWERON` (Generic/HTC) thường thấy trên các dòng màn hình xe Trung Quốc.
*   **Tầng 2: Cửa sổ phục hồi (Recovery Window)**
    *   Nếu tín hiệu Boot bị bỏ lỡ, ứng dụng sẽ theo dõi các sự kiện như nạp nguồn USB hoặc thay đổi trạng thái mạng trong vòng 90-120 giây đầu tiên để tự kích hoạt lại.
*   **Tầng 3: Cơ chế "Phát bù" (Visible Compatibility Path)**
    *   Đây là tính năng độc đáo nhất. Nếu hệ thống chặn khởi động ngầm, ngay khi ứng dụng xuất hiện trên màn hình (do Launcher tự mở hoặc người dùng nhấn vào) trong vòng 5 phút sau khi máy bật (`uptime < 300s`), ứng dụng sẽ tự động nhận diện đây là một phiên khởi động mới và thực hiện phát lời chào ngay lập tức.

## 2. Logic Kiểm Soát Vận Hành (Runtime Policies)

Để tránh các lỗi gây phiền toái, ứng dụng áp dụng các chính sách sau:

1.  **Chống phát lặp (Deduplication):** Trong cửa sổ 15 giây (`BOOT_INIT_DEDUPE_WINDOW`), mọi tín hiệu khởi động trùng lặp sẽ bị loại bỏ.
2.  **Kiểm tra tính sẵn sàng của Cache:** Trước khi phát, ứng dụng kiểm tra file âm thanh trong Cache. Nếu file hỏng hoặc mất, nó sẽ tự động kích hoạt lệnh `REFETCH_FROM_SERVER` để tải lại.
3.  **Quản lý tranh chấp âm thanh (Audio Focus):** Ứng dụng yêu cầu quyền ưu tiên âm thanh. Nếu bị từ chối (do Radio xe đang chiếm dụng mạnh), nó sẽ thực hiện chiến lược `takeoverCount` để thử lại khi có cơ hội.

## 3. Kết Thúc & Duy Trì

*   Sau khi phát xong, ứng dụng có thể tự động đóng Activity (`finishActivityOnCompletion`) để trả lại màn hình cho ứng dụng dẫn đường.
*   Duy trì một Foreground Service nhẹ để quản lý nút điều khiển nổi (Floating Button) nếu người dùng có nhu cầu điều khiển nhạc thủ công.
