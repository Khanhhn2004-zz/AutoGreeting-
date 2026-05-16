# Tối ưu thiết bị, quyền Android và hành vi OEM

AutoGreeting chạy trên môi trường phức tạp hơn điện thoại thông thường: màn hình Android xe hơi, Android Automotive, Android Box gắn thêm, custom ROM và các cơ chế sleep/wake khác nhau. Tài liệu này tập trung vào những điểm cần cấu hình và kiểm thử để app phát lời chào đúng lúc, đồng thời giữ âm thanh tạm biệt ở dạng thủ công hoặc fallback khi thiết bị vẫn còn hoạt động.

## 1. Môi trường mục tiêu

Manifest khai báo:

```xml
<uses-feature android:name="android.hardware.type.automotive" android:required="true"/>
```

Điều này phù hợp nếu app chỉ phân phối cho thiết bị automotive. Nếu cần cài trên Android Box không khai báo hardware automotive, cần kiểm tra lại `required="true"` vì nó có thể làm Google Play lọc thiết bị.

Thiết bị mục tiêu thường thuộc các nhóm:

| Nhóm | Đặc điểm | Rủi ro |
| --- | --- | --- |
| Android Automotive chuẩn | Có lifecycle xe rõ hơn, policy chặt hơn | Quyền nền và automotive UX bị kiểm soát. |
| Android Box gắn thêm | Dễ sideload APK, nhiều ROM tùy biến | Boot/ACC action không chuẩn, kill service mạnh. |
| Màn hình OEM theo xe | Có signal ACC riêng | Khó debug, settings bị ẩn, broadcast private. |
| Emulator | Dễ build/test UI | Không đại diện cho ACC thật. |

## 2. Danh sách quyền

| Quyền | Lý do dùng | Cần test |
| --- | --- | --- |
| `INTERNET` | Login, heartbeat, check update, download audio. | Mất mạng, mạng yếu, backend lỗi. |
| `RECEIVE_BOOT_COMPLETED` | Start runtime sau boot. | Cold boot, reboot, direct boot behavior. |
| `FOREGROUND_SERVICE` | Chạy `CoreService` và `FloatingButtonService`. | Notification hiển thị, service không bị chặn. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ special use foreground service. | Manifest + runtime startForeground. |
| `SYSTEM_ALERT_WINDOW` | Hiển thị floating button trên app khác. | Cấp quyền, thu hồi quyền, UI settings OEM. |
| `WAKE_LOCK` | Dự phòng giữ CPU cho xử lý nền. | Không lạm dụng gây hao pin. |
| `BIND_ACCESSIBILITY_SERVICE` | Accessibility fallback. | Người dùng bật thủ công, giải thích chính sách. |

## 3. Runtime permission flow

### Overlay

`MainActivity` kiểm tra:

```kotlin
Settings.canDrawOverlays(this)
```

Nếu thiếu quyền, mở:

```kotlin
Settings.ACTION_MANAGE_OVERLAY_PERMISSION
```

Checklist:

- [ ] Bật switch Floating Button khi chưa có quyền.
- [ ] Settings mở đúng app package.
- [ ] Sau khi cấp quyền và quay lại app, service start.
- [ ] Overlay hiển thị.
- [ ] Tắt switch, overlay biến mất.
- [ ] Thu hồi quyền trong Settings, app không crash.

### Accessibility

Accessibility Service được khai báo trong Manifest và trỏ tới:

```text
app/src/main/res/xml/accessibility_service_config.xml
```

Người dùng phải bật thủ công trong Settings. Nếu đưa lên Google Play, cần giải thích rõ:

- App không đọc nội dung cá nhân.
- Mục đích là tăng khả năng sống sót hoặc fallback trên màn hình xe đặc thù.
- Không dùng Accessibility để tự động thao tác UI ngoài phạm vi sản phẩm.

## 4. Boot, quick boot và ACC intents

Source hiện nghe 4 action:

```text
android.intent.action.BOOT_COMPLETED
android.intent.action.QUICKBOOT_POWERON
android.intent.action.ACC_ON
android.intent.action.ACC_OFF
```

Vấn đề thực tế:

- Một số màn Android Box không reboot khi tắt xe, chỉ sleep.
- Một số ROM dùng action OEM riêng.
- Một số ROM không phát `ACC_OFF`; nhiều thiết bị tắt hoặc ngủ ngay khi mất ACC nên không thể phát âm thanh sau sự kiện này.
- Một số ROM chặn broadcast tới app bên thứ ba.

Quy tắc thêm action mới:

1. Có logcat từ thiết bị thật.
2. Ghi lại hãng, model, Android version.
3. Ghi lại action nhận được.
4. Thêm vào allowlist có test hoặc checklist regression.
5. Cập nhật `docs/MAIN_FLOW.md` và `RELEASE_GATE.md`.

Ví dụ cần thu thập:

```powershell
adb logcat | Select-String -Pattern "ACC|BOOT|QUICKBOOT|BootReceiver|CoreService"
```

## 5. Battery optimization và auto-start

Nhiều màn hình xe có trình quản lý pin riêng. Foreground Service không luôn đủ để tránh bị kill.

Checklist cấu hình thiết bị:

- [ ] Đưa AutoGreeting vào Battery Optimization -> Unrestricted.
- [ ] Cho phép Auto-start nếu ROM có mục này.
- [ ] Cho phép chạy nền.
- [ ] Tắt task cleaner tự động nếu có.
- [ ] Không khóa app khỏi recent apps nếu ROM kill app khi swipe.
- [ ] Kiểm tra lại sau khi xe sleep qua đêm.

Hướng dẫn kỹ thuật viên nên ghi rõ theo từng hãng màn hình nếu đã test:

| Thiết bị | Android | Cần tắt tối ưu pin | Cần bật auto-start | ACC action xác nhận | Ghi chú |
| --- | --- | --- | --- | --- | --- |
| Chưa kiểm tra | - | - | - | - | Cần bổ sung sau QA thực tế. |

## 6. Foreground Service behavior

`CoreService`:

- Notification channel: `AutoGreetingService`.
- Notification title: `Auto Greeting`.
- Chạy heartbeat mỗi 30 giây.
- Start foreground với `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` trên Android 14+.

`FloatingButtonService`:

- Notification channel: `floating_button_service`.
- Notification title: `Auto Greeting`.
- Cũng dùng special use trên Android 14+.

Rủi ro:

- Hai foreground service cùng chạy có thể gây nhiều notification hoặc tăng mức nhạy cảm policy.
- Heartbeat mỗi 30 giây có thể quá dày cho xe để lâu.
- Android 14 yêu cầu special use justification rõ ràng.

Khuyến nghị:

- Ghi rõ trong release notes vì sao cần service nền.
- Cân nhắc backoff heartbeat khi xe sleep hoặc không có mạng.
- Kiểm tra pin/CPU trên thiết bị thật.

## 7. Overlay và Compose trên WindowManager

`FloatingButtonService` render Compose ngoài Activity bằng cách tự cấp lifecycle owner. Đây là phần nhạy cảm với OEM vì overlay window policy khác nhau.

Checklist:

- [ ] Overlay hiển thị trên launcher.
- [ ] Overlay hiển thị trên bản đồ.
- [ ] Overlay không lấy focus bàn phím.
- [ ] Kéo thả không làm crash.
- [ ] Xoay màn hình hoặc thay đổi resolution không làm overlay kẹt ngoài màn hình.
- [ ] Stop service remove view.
- [ ] Start service lại không tạo nhiều view trùng.

Rủi ro cần test thêm:

- `WindowManager.addView(...)` có thể throw nếu thiếu overlay permission.
- OEM có thể chặn overlay trên một số app hệ thống.
- Pill có vị trí ban đầu cố định `x=100`, `y=100`, có thể không phù hợp mọi màn hình.

## 8. Audio playback và âm thanh xe

`SoundPlayerService` dùng `MediaPlayer` trực tiếp.

Hiện đã có:

- Stop sound hiện tại trước khi play sound mới.
- Reset state khi completion.
- Release player khi stop/destroy.

Chưa có:

- `AudioManager.requestAudioFocus(...)`.
- Chính sách duck nhạc nền.
- Fallback sang file mặc định nếu URI không hợp lệ.
- Validate file type ngoài MIME picker.

Checklist QA:

- [ ] Phát sound 1 từ dashboard hoặc floating button.
- [ ] Phát sound 2 từ dashboard hoặc floating button.
- [ ] Stop sound đang phát.
- [ ] Phát sound 1 rồi bấm sound 2, sound 1 dừng.
- [ ] Xóa file hoặc thu hồi URI permission, app không crash.
- [ ] Radio/nhạc nền không bị chồng âm thanh quá khó chịu.

## 9. Network và backend

App dùng cleartext HTTP:

```text
android:usesCleartextTraffic="true"
```

và base URL:

```text
http://103.118.28.117/api/
```

Rủi ro:

- Token đi qua HTTP nếu không có lớp bảo vệ ngoài app.
- Google Play hoặc security review có thể chặn.
- Thiết bị xe đi vào hầm hoặc mất sóng khiến heartbeat/download fail.

Checklist:

- [ ] Xác nhận backend production có HTTPS hoặc chấp nhận rủi ro HTTP.
- [ ] Không log token/password.
- [ ] Heartbeat fail không crash.
- [ ] Download fail trả retry khi worker hoàn thiện.
- [ ] Base URL release không trỏ sai môi trường.

## 10. Storage và file âm thanh

Âm thanh người dùng chọn được lưu dưới dạng URI trong DataStore.

Điều kiện cần:

- App phải giữ persistable read permission.
- URI không được blank khi autoplay.
- `MediaPlayer.create(...)` phải đọc được URI.

Nếu sau này dùng file sync từ server:

- Lưu file vào internal storage.
- Lưu path/URI rõ sound 1 hay sound 2.
- Không ghi đè file user tự chọn nếu chưa có policy.
- Có checksum hoặc kiểm tra file size để tránh file hỏng.

## 11. Ma trận QA thiết bị

| Scenario | Emulator | Android Box | Màn xe thật | Bắt buộc trước release |
| --- | --- | --- | --- | --- |
| Build/install/open app | Có thể | Có | Có | Có |
| Chọn file audio | Có thể | Có | Có | Có |
| Overlay permission | Có thể | Có | Có | Có |
| Floating button trên app khác | Có thể | Có | Có | Có |
| BOOT_COMPLETED | Có thể giả lập | Có | Có | Có |
| QUICKBOOT_POWERON | Khó | Có thể | Có thể | Nếu thiết bị hỗ trợ |
| ACC_ON | Thường không | Có thể | Có | Có nếu quảng bá tính năng |
| ACC_OFF | Thường không | Có thể | Có thể | Chỉ ghi nhận chẩn đoán, không coi là tiêu chí phát tạm biệt bắt buộc |
| Sleep/wake qua đêm | Không đại diện | Có | Có | Có |
| Battery optimization | Một phần | Có | Có | Có |
| Backend heartbeat | Có | Có | Có | Có nếu release dùng server |

## 12. Lệnh validation tập trung

Build nếu có wrapper:

```powershell
.\gradlew.bat :app:assembleDebug
```

Build nếu dùng Gradle cài sẵn:

```powershell
gradle :app:assembleDebug
```

Cài APK:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Mở app:

```powershell
adb shell monkey -p com.example.carchatbot 1
```

Broadcast test:

```powershell
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.example.carchatbot
adb shell am broadcast -a android.intent.action.QUICKBOOT_POWERON -p com.example.carchatbot
adb shell am broadcast -a android.intent.action.ACC_ON -p com.example.carchatbot
adb shell am broadcast -a android.intent.action.ACC_OFF -p com.example.carchatbot
```

Logcat filter:

```powershell
adb logcat -s BootReceiver CoreService SoundPlayerService DownloadWorker
```

Kiểm tra service:

```powershell
adb shell dumpsys activity services com.example.carchatbot
```

## 13. Posture nhạy cảm release đã biết

Không nên phát hành rộng nếu chưa xử lý hoặc chấp nhận rõ các điểm sau:

- Login đang mô phỏng.
- DownloadWorker chưa sync thật.
- Base URL HTTP hard-code.
- Chưa có audio focus.
- Chưa có QA matrix thiết bị thật.
- Chưa có test tự động trong workspace local.
- Overlay và Accessibility là quyền nhạy cảm.
- ACC action có thể không tồn tại trên nhiều màn hình.

## 14. Quy tắc cập nhật tài liệu thiết bị

Sau mỗi đợt test thiết bị thật, cập nhật tài liệu này với:

- Hãng/model thiết bị.
- Android version.
- Có/không có overlay settings.
- Có/không có battery unrestricted.
- Action boot/ACC quan sát được.
- Kết quả sleep/wake.
- Vấn đề playback.
- Logcat hoặc ghi chú support.

Không dùng kết quả emulator để kết luận app đã ổn trên xe thật.
