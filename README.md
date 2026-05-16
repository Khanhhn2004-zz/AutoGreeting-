# AutoGreeting

Giải pháp tối ưu để tự động hóa lời chào và âm thanh khởi động trên màn hình Android ô tô (Head Unit). Không giống như các công cụ tự động hóa thông thường, AutoGreeting được thiết kế với các cơ chế phục hồi chuyên sâu để đảm bảo hoạt động ổn định ngay cả khi hệ thống của xe gặp lỗi hoặc bị hạn chế.

## Tính năng chính

| Phân loại | Mô tả chi tiết |
|-----------|---------------|
| Đa tầng kích hoạt | Xử lý linh hoạt các tín hiệu Boot, QuickBoot (xe Trung Quốc) và các sự kiện thức dậy từ chế độ Sleep của xe. |
| Cơ chế phát bù | Tự động nhận diện và phát lời chào ngay cả khi tín hiệu khởi động ngầm bị hệ thống Android chặn (Visible Compatibility Path). |
| Chống lặp thông minh | Thuật toán Deduplication ngăn chặn việc phát lời chào nhiều lần do tín hiệu Boot không ổn định của Head Unit. |
| Điều khiển nổi | Nút điều khiển Overlay giúp tương tác nhanh với âm thanh mà không làm gián đoạn ứng dụng dẫn đường (Google Maps, Navitel). |
| Chẩn đoán chuyên sâu | Hệ thống Diagnostics Report phân tích chi tiết từng giai đoạn (Trigger -> Focus -> Playback) để tìm nguyên nhân lỗi chính xác. |

## Công nghệ và Kiến trúc

| Thành phần | Công nghệ sử dụng |
|------------|-------------------|
| Ngôn ngữ | Kotlin |
| Giao diện UI | Jetpack Compose (Modern UI Framework) |
| Lớp chính sách | AppRuntimePolicies (Quản lý cửa sổ thời gian và phục hồi lỗi) |
| Dependency Injection | Hilt |
| Xử lý ngầm | Foreground Services, Broadcast Receivers đa tầng |
| Lưu trữ dữ liệu | Jetpack DataStore (Chống hỏng dữ liệu khi mất điện đột ngột) |
| Xử lý Media | Multi-Engine (MediaPlayer / ExoPlayer) |

## Cấu trúc dự án

```text
AutoGreeting/
├── car/                    # Module chính xử lý logic xe hơi
│   └── src/main/java/.../carchatbot/
│       ├── boot/           # Xử lý đa tầng tín hiệu Boot & QuickBoot
│       ├── runtime/        # AppRuntimePolicies (Trái tim điều khiển hệ thống)
│       ├── service/        # Các dịch vụ Core, SoundPlayer và Overlay
│       ├── ui/             # Giao diện Compose hiện đại
│       ├── support/        # Hệ thống chẩn đoán lỗi chuyên sâu (Diagnostics)
│       └── utils/          # Công cụ tối ưu hóa thiết bị và xử lý file âm thanh
├── docs/                   # Tài liệu chi tiết (Main Flow, Architecture, Device Optimization)
└── README.md
```

## Bản quyền

Dự án thuộc sở hữu của Hạ Ngọc Khánh.
