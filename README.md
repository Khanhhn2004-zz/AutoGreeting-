# AutoGreeting

Ứng dụng Android chuyên dụng để cá nhân hóa trải nghiệm khởi động xe hơi bằng các âm thanh chào mừng tự động. Dự án tập trung vào độ tin cậy của việc kích hoạt dịch vụ ngay khi hệ thống Android của xe (Head Unit) hoàn tất quá trình Boot.

## Tính năng chính

| Phân loại | Mô tả chi tiết |
|-----------|---------------|
| Tự khởi động | Tự động kích hoạt ngay khi nhận sự kiện `BOOT_COMPLETED` từ hệ thống Android của xe. |
| Phát âm thanh | Phát lời chào hoặc bản nhạc đã cấu hình với khả năng ưu tiên âm thanh cao khi khởi động. |
| Nút điều khiển nổi | Floating Button hiển thị trên các ứng dụng dẫn đường, cho phép thao tác nhanh mà không cần mở app. |
| Quản lý phiên | Hệ thống đăng nhập bảo mật để đồng bộ hóa và lưu trữ các tùy chỉnh cá nhân. |
| Hệ thống chẩn đoán | Tự động tạo báo cáo log và chẩn đoán trạng thái vận hành để hỗ trợ xử lý sự cố. |

## Công nghệ và Kiến trúc

| Thành phần | Công nghệ sử dụng |
|------------|-------------------|
| Ngôn ngữ | Kotlin |
| Kiến trúc | MVVM |
| Giao diện UI | Jetpack Compose (Modern UI Framework) |
| Dependency Injection | Hilt |
| Xử lý ngầm | Foreground Services, Broadcast Receivers |
| Lưu trữ dữ liệu | Jetpack DataStore |
| Xử lý Media | Android MediaPlayer / ExoPlayer |

## Cấu trúc dự án

```text
AutoGreeting/
├── car/                    # Module chính chứa Logic xử lý
│   └── src/main/java/.../carchatbot/
│       ├── boot/           # BootCompletedReceiver (Trigger khởi động)
│       ├── service/        # CoreService, SoundPlayerService, FloatingButtonService
│       ├── ui/             # Giao diện Compose (Main, Login, Permissions)
│       ├── data/           # Repository, DataStore, Preferences
│       ├── support/        # LogExporter, Diagnostics Report
│       └── utils/          # AutoStartHelper, DeviceUtils (OEM optimization)
├── docs/                   # Tài liệu chi tiết (Main Flow, Architecture, Device)
└── README.md
```

## Bản quyền

Dự án thuộc sở hữu của Hạ Ngọc Khánh.
