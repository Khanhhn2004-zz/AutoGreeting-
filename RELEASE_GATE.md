# Cổng kiểm soát phát hành AutoGreeting

Tài liệu này là checklist bắt buộc trước khi tạo APK để cài lên màn hình xe của khách hàng hoặc trước khi cân nhắc phân phối qua Google Play. AutoGreeting chạy service nền, nhận boot/ACC broadcast, dùng overlay và có Accessibility Service, vì vậy release phải được chặn bằng bằng chứng runtime chứ không chỉ bằng source review.

## Cổng 0 - Quyết định phạm vi phân phối

- [ ] Xác định bản phát hành là APK cài tay, bản test nội bộ hay Google Play.
- [ ] Xác định thiết bị mục tiêu: Android Automotive, Android Box gắn thêm, hay màn hình xe OEM cụ thể.
- [ ] Xác định Android version mục tiêu thấp nhất và cao nhất.
- [ ] Xác định có cần Accessibility Service trong bản phát hành này hay chỉ giữ như fallback.
- [ ] Xác định backend environment dùng cho build: test, staging hay production.

Blocker:

- Không phát hành nếu chưa biết app sẽ được cài theo kênh nào.
- Không phát hành Google Play nếu chưa có lý do chính sách cho overlay, foreground special use và accessibility.

## Cổng 1 - Sẵn sàng build

- [ ] Source build được từ command line hoặc Android Studio.
- [ ] Nếu dùng command line, root project có `gradlew` / `gradlew.bat` hoạt động.
- [ ] `compileSdk`, `targetSdk`, `minSdk`, `applicationId`, `versionCode`, `versionName` được xác nhận.
- [ ] Manifest không thiếu service, receiver hoặc permission bắt buộc.
- [ ] APK debug hoặc release được tạo ở đường dẫn đã ghi lại.
- [ ] Không commit artifact build như APK debug vào source chính nếu không có lý do phân phối rõ ràng.

Blocker:

- Build không tái lập được trên máy khác.
- README hướng dẫn lệnh build không tồn tại trong workspace.

## Cổng 2 - Kiểm thử khói khi mở app

- [ ] Cài APK thành công.
- [ ] Mở app không crash.
- [ ] Khi chưa có token, app hiển thị LoginScreen hoặc cho phép skip test mode theo thiết kế hiện tại.
- [ ] Sau khi vào Dashboard, các section chính hiển thị: lời chào, tạm biệt, tự động phát, phím điều khiển, YouTube Shortcut, đăng xuất.
- [ ] Chọn file âm thanh cho sound 1 và sound 2 bằng Android file picker.
- [ ] Sau restart app, URI âm thanh vẫn còn trong DataStore.
- [ ] Logout xóa token và đưa app về trạng thái đăng nhập.

Blocker:

- App crash ở first launch.
- Không chọn được file audio.
- Logout không xóa trạng thái đăng nhập.

## Cổng 3 - Quyền Android và overlay

- [ ] `SYSTEM_ALERT_WINDOW` được xin bằng flow rõ ràng từ `MainActivity`.
- [ ] Khi người dùng cấp overlay permission, `FloatingButtonService` start được.
- [ ] Khi người dùng từ chối overlay permission, app không crash.
- [ ] Floating Button hiển thị trên ít nhất một app khác.
- [ ] Tắt switch Floating Button remove overlay.
- [ ] Foreground notification của `CoreService` và `FloatingButtonService` hiển thị đúng.
- [ ] Accessibility Service có mô tả đúng mục đích trong cấu hình XML và trong tài liệu người dùng.

Blocker:

- `FloatingButtonService` crash khi thiếu overlay permission.
- Overlay không biến mất khi tắt tính năng.
- Android 14 chặn Foreground Service vì khai báo thiếu hoặc sai type.

## Cổng 4 - Xác thực boot, quick boot và ACC

- [ ] `BootCompletedReceiver` nhận được `BOOT_COMPLETED` trên thiết bị test.
- [ ] `QUICKBOOT_POWERON` được kiểm tra trên thiết bị có hỗ trợ hoặc ghi rõ không hỗ trợ.
- [ ] `ACC_ON` được kiểm tra trên màn hình xe thật hoặc thiết bị phát broadcast tương đương.
- [ ] `ACC_OFF` nếu có được ghi nhận như tín hiệu chẩn đoán, không dùng làm tiêu chí bắt buộc để phát tạm biệt.
- [ ] Logcat ghi nhận action trong tag `BootReceiver`.
- [ ] `CoreService` nhận action và route sound ID đúng.

Blocker:

- Không có bằng chứng boot/ACC trên thiết bị mục tiêu.
- Bản release quảng bá lời tạm biệt tự động ở thời điểm xe tắt, trong khi thiết bị mục tiêu tắt hoặc ngủ ngay khi mất ACC.
- Thiết bị mục tiêu dùng action OEM khác nhưng chưa được thêm hoặc ghi rõ hạn chế.

## Cổng 5 - Xác thực autoplay và playback

- [ ] Auto Play bật: boot/ACC ON phát sound 1 nếu URI tồn tại.
- [ ] Auto Play không quảng bá ACC OFF như trigger bắt buộc cho sound 2.
- [ ] Auto Play tắt: boot/ACC không phát âm thanh.
- [ ] Khi URI trống, app không crash và không tạo playback rỗng.
- [ ] Floating Button phát/dừng sound 1.
- [ ] Floating Button phát/dừng sound 2.
- [ ] Không phát chồng hai sound cùng lúc.
- [ ] `MediaPlayer` được release sau khi stop hoặc completion.

Blocker:

- App phát sai sound ID.
- App phát chồng nhiều audio.
- MediaPlayer không release sau khi phát.
- App crash khi file âm thanh bị xóa hoặc mất quyền đọc.

## Cổng 6 - Xác thực backend, heartbeat và sync

- [ ] Base URL đúng môi trường release.
- [ ] Login thật được xác nhận nếu bản release yêu cầu đăng nhập thật.
- [ ] Token được lưu vào DataStore sau login thật.
- [ ] Heartbeat gửi `Authorization: Bearer <token>`.
- [ ] Khi token không tồn tại, `CoreService` tự dừng như thiết kế.
- [ ] Mất mạng không làm app crash.
- [ ] Nếu quảng bá tính năng sync âm thanh, `DownloadWorker` phải thật sự gọi `check-update`, tải file và cập nhật local storage.

Blocker:

- Build release vẫn dùng backend test ngoài ý muốn.
- Heartbeat chạy bằng token mock trong bản production.
- Tài liệu hoặc UI nói có đồng bộ âm thanh trong khi `DownloadWorker.doWork()` vẫn là placeholder.

## Cổng 7 - Xác thực nền, pin và hành vi OEM

- [ ] Kiểm tra app sau cold boot.
- [ ] Kiểm tra app sau sleep/wake.
- [ ] Kiểm tra khi màn hình xe khóa hoặc tắt màn.
- [ ] Kiểm tra sau khi Android/OEM kill app từ recent apps.
- [ ] Kiểm tra khi Battery Optimization đang bật.
- [ ] Kiểm tra sau khi đưa app vào whitelist/unrestricted.
- [ ] Ghi lại hướng dẫn riêng cho thiết bị OEM nếu cần.

Blocker:

- App bị kill ngay sau khi xe sleep/wake trên thiết bị mục tiêu mà chưa có hướng dẫn workaround.
- Heartbeat loop hoặc service tiêu thụ tài nguyên bất thường.

## Cổng 8 - Rà soát rủi ro Google Play

- [ ] `FOREGROUND_SERVICE_SPECIAL_USE` có mô tả use case rõ ràng.
- [ ] `SYSTEM_ALERT_WINDOW` có lý do trực tiếp liên quan tới floating control khi lái xe.
- [ ] `BIND_ACCESSIBILITY_SERVICE` có mô tả hạn chế, không thu thập nội dung nhạy cảm.
- [ ] `usesCleartextTraffic="true"` được đánh giá lại. Nếu giữ, phải có lý do và phạm vi backend rõ.
- [ ] Không log token, password, file path nhạy cảm hoặc dữ liệu người dùng không cần thiết.
- [ ] Có privacy note nếu app gửi heartbeat/log về server.

Blocker:

- Không có policy explanation cho quyền nhạy cảm.
- Cleartext HTTP được dùng trong build công khai mà không có chấp thuận rủi ro.
- Accessibility bị mô tả sai mục đích.

## Cổng 9 - Đồng bộ tài liệu

- [ ] `README.md` khớp trạng thái code hiện tại.
- [ ] `PLAN.md` ghi đúng backlog và ưu tiên.
- [ ] `RELEASE_GATE.md` có checklist tương ứng với quyền và service đang khai báo.
- [ ] `docs/TECHNICAL_ARCHITECTURE.md` khớp module, package, dependency và service thực tế.
- [ ] `docs/MAIN_FLOW.md` khớp trigger và route runtime.
- [ ] `docs/DEVICE_OPTIMIZATION.md` có ma trận QA thiết bị hoặc ghi rõ còn thiếu.
- [ ] Không mô tả placeholder như feature production-ready.

Blocker:

- Tài liệu nói login/sync/audio focus đã hoàn thiện trong khi source chưa có.
- Link tài liệu bị hỏng.

## Lệnh validation tập trung

Nếu có Gradle wrapper:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Nếu wrapper script chưa có nhưng Gradle đã được cài:

```powershell
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

Gửi broadcast giả lập bằng adb, tùy thiết bị và policy Android:

```powershell
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.example.carchatbot
adb shell am broadcast -a android.intent.action.QUICKBOOT_POWERON -p com.example.carchatbot
adb shell am broadcast -a android.intent.action.ACC_ON -p com.example.carchatbot
# ACC_OFF chỉ dùng để quan sát nếu thiết bị còn runtime; không phải tiêu chí phát tạm biệt bắt buộc.
adb shell am broadcast -a android.intent.action.ACC_OFF -p com.example.carchatbot
```

Theo dõi log:

```powershell
adb logcat -s BootReceiver CoreService SoundPlayerService DownloadWorker
```

## Quy tắc đóng release

Một release chỉ được coi là đủ điều kiện giao cho khách khi:

- Cổng 1, 2, 3 và 5 pass trên ít nhất một thiết bị gần thực tế.
- Cổng 4 pass trên thiết bị mục tiêu nếu release quảng bá boot/ACC automation.
- Cổng 6 pass nếu release yêu cầu login, heartbeat hoặc sync server thật.
- Cổng 8 pass nếu phân phối qua Google Play.
- Mọi blocker còn lại có quyết định chấp nhận rủi ro bằng văn bản.

Không được claim "ready", "safe" hoặc "production-ready" nếu chỉ mới kiểm tra bằng đọc source.
