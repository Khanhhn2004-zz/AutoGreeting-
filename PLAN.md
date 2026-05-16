# Kế hoạch kỹ thuật AutoGreeting

Tài liệu này mô tả hướng phát triển kỹ thuật cho source local đang mở. Mục tiêu là giữ ứng dụng ổn định trên màn hình Android xe hơi, không viết quá mức những gì code hiện chưa làm, và ưu tiên các hạng mục có ảnh hưởng trực tiếp đến khả năng phát lời chào đúng thời điểm.

## 1. Ý định sản phẩm

AutoGreeting là app nền cho màn hình xe:

- Tự phát lời chào khi xe bật điện hoặc khởi động.
- Cung cấp âm thanh tạm biệt để phát thủ công khi thiết bị vẫn còn hoạt động. Không cam kết tự phát sau `ACC_OFF`, vì phần lớn màn hình sẽ tắt hoặc ngủ ngay khi mất ACC.
- Cho phép kỹ thuật viên hoặc người dùng chọn file âm thanh riêng.
- Cho phép điều khiển nhanh bằng floating button khi đang dùng app khác như bản đồ.
- Gửi heartbeat về server để theo dõi tình trạng kết nối.
- Có đường mở rộng để đồng bộ file âm thanh mới từ server.

Sản phẩm không phải launcher thay thế, không phải app nghe nhạc, và không nên chiếm quyền điều khiển âm thanh lâu hơn thời lượng lời chào/tạm biệt.

## 2. Baseline kiến trúc hiện tại

Source hiện tại là Android app module `app`, package `com.example.carchatbot`.

Các thành phần đã có:

- `MainActivity`: gate login/dashboard và xin quyền overlay.
- `LoginScreen` / `LoginViewModel`: UI đăng nhập, hiện login được mô phỏng.
- `DashboardScreen` / `MainViewModel`: cấu hình sound, Auto Play, Floating Button, YouTube link.
- `BootCompletedReceiver`: nhận boot, quick boot, ACC ON và có khai báo ACC OFF cho mục đích fallback/chẩn đoán, nhưng ACC OFF không được coi là trigger phát tạm biệt đáng tin cậy.
- `CoreService`: Foreground Service điều phối autoplay, floating button, heartbeat và update worker.
- `FloatingButtonService`: overlay Compose trên `WindowManager`.
- `SoundPlayerService`: playback bằng `MediaPlayer`.
- `UserPreferencesRepository`: DataStore cho token, sound URI và cờ cấu hình.
- `IotStatusRepository`: login và heartbeat repository.
- `DownloadWorker`: khung tải file, chưa nối logic đồng bộ thật.

Các điểm chưa hoàn thiện:

- Login thật chưa nối từ `LoginViewModel` sang `IotStatusRepository.login(...)`.
- `IotStatusRepository.login(...)` chưa lưu token vào DataStore.
- `DownloadWorker.doWork()` chưa gọi `check-update`, chưa download file thật, chưa cập nhật sound URI.
- YouTube shortcut đang có UI nhưng handler còn placeholder.
- Chưa có runtime guard rõ ràng khi bật floating button nhưng chưa có overlay permission trong service.
- Chưa thấy test source trong workspace local.
- Workspace thiếu `gradlew` / `gradlew.bat` script dù có `gradle/wrapper/gradle-wrapper.properties`.

## 3. Quy tắc phát triển

### Giữ nguyên gate boot/ACC

Không thay đổi danh sách action hoặc cách route boot/ACC nếu chưa có logcat từ thiết bị thật. Mọi action OEM mới cần được thêm có kiểm soát và ghi rõ nguồn thiết bị.

### Tách manual play khỏi autoplay

Manual play từ Floating Button và autoplay từ `CoreService` phải có cùng backend playback (`SoundPlayerService`) nhưng khác trigger. Không để thao tác thủ công vô tình bật autoplay.

### Không bỏ qua token gate

`CoreService` hiện dừng nếu không có token. Đây là gate quan trọng để tránh service nền chạy vô hạn sau logout. Nếu sau này cho phép offline playback, cần thiết kế rõ chế độ offline và điều kiện dừng service.

### Không mở rộng quyền âm thầm

Quyền overlay, accessibility, foreground special use và boot receiver đều nhạy cảm. Mọi thay đổi quyền phải đi kèm:

- Lý do sản phẩm.
- UX hướng dẫn cấp quyền.
- Rủi ro Google Play nếu có.
- Test trên Android 12, 13, 14 nếu có thiết bị.

### Cleanup service phải rõ

Mọi service cần release tài nguyên trong `onDestroy()`:

- `CoreService`: remove callbacks và cancel coroutine job.
- `SoundPlayerService`: stop/release MediaPlayer.
- `FloatingButtonService`: remove overlay view, clear ViewModelStore, dispatch lifecycle destroy.

Không thêm vòng lặp nền mới nếu không có điểm dừng và log kiểm chứng.

### Backend config không hard-code lâu dài

Base URL hiện nằm trực tiếp trong `NetworkModule`. Trước release rộng, cần chuyển sang `BuildConfig` hoặc cấu hình build variant để tránh build nhầm môi trường.

## 4. Nhóm việc ưu tiên

### P0 - Tài liệu và release clarity

- Viết lại `README.md`, `PLAN.md`, `RELEASE_GATE.md` và 3 file trong `docs/` theo style BlueCruise.
- Ghi rõ trạng thái code đã có và chưa có.
- Bổ sung release gate cho màn hình xe, overlay, ACC event và Google Play risk.

Tiêu chí hoàn tất:

- 6 file Markdown tồn tại và link chéo đúng.
- README có phần "Trạng thái đã xác minh từ code".
- Release gate phân biệt build debug, device smoke test, real ACC validation và Google Play review.

### P0 - Build reproducibility

- Bổ sung hoặc khôi phục `gradlew` và `gradlew.bat` nếu workspace này sẽ là source chính.
- Chạy `:app:assembleDebug` từ command line.
- Ghi lại đường dẫn APK output chính thức.

Tiêu chí hoàn tất:

- Người khác clone source có thể build không cần cấu hình thủ công ngoài Android SDK/JDK.
- README không hướng dẫn lệnh thiếu file.

### P1 - Login thật và token lifecycle

- Nối `LoginViewModel.login(...)` vào `IotStatusRepository.login(...)`.
- Lưu `access_token` vào `UserPreferencesRepository`.
- Hiển thị lỗi đăng nhập rõ ràng.
- Xử lý token hết hạn khi heartbeat trả fail.

Tiêu chí hoàn tất:

- Login thành công đưa người dùng vào Dashboard.
- Logout xóa token và stop các bề mặt chạy nền liên quan.
- Heartbeat không dùng token mock khi đã logout.

### P1 - Overlay permission UX

- Kiểm tra trạng thái `Settings.canDrawOverlays(...)` trước khi start `FloatingButtonService`.
- Nếu quyền bị thu hồi, tắt switch hoặc hiển thị màn hướng dẫn.
- Không để service crash vì thiếu quyền.

Tiêu chí hoàn tất:

- Bật Floating Button khi chưa có quyền sẽ mở đúng màn Settings.
- Sau khi cấp quyền, overlay xuất hiện.
- Khi tắt switch, overlay biến mất.

### P1 - ACC/OEM validation

- Thu thập logcat từ ít nhất 3 loại thiết bị: emulator/Android Automotive, Android Box phổ thông, màn hình xe thực tế.
- Xác minh `BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `ACC_ON`.
- Nếu thiết bị có phát `ACC_OFF`, chỉ ghi nhận như dữ liệu chẩn đoán. Không dùng làm tiêu chí bắt buộc cho sound 2.
- Ghi lại action OEM nếu khác chuẩn.

Tiêu chí hoàn tất:

- Có ma trận thiết bị trong `docs/DEVICE_OPTIMIZATION.md`.
- Có log bằng chứng cho từng action.
- Có fallback plan nếu thiết bị không bắn ACC intent.

### P1 - Playback hardening

- Thêm `AudioManager.requestAudioFocus(...)`.
- Chọn chính sách duck hoặc transient focus phù hợp.
- Không phát chồng hai âm thanh.
- Xử lý URI mất quyền, file bị xóa, MediaPlayer create fail.

Tiêu chí hoàn tất:

- Radio/nhạc nền bị duck hoặc tạm giảm đúng kỳ vọng.
- Phát xong release focus.
- Log lỗi đủ để support kiểm tra.

### P2 - Đồng bộ âm thanh từ server

- Hoàn thiện `DownloadWorker.doWork()`.
- Gọi `check-update`.
- Tải file bằng endpoint streaming.
- Lưu file vào internal storage.
- Cập nhật URI hoặc path vào DataStore.
- Retry khi mất mạng.

Tiêu chí hoàn tất:

- Không làm mất sound local người dùng tự chọn nếu server không có update.
- Mất mạng trả `Result.retry()`.
- Tải thành công có log và cập nhật cấu hình rõ ràng.

### P2 - YouTube shortcut

- Xác định mục tiêu thật của `YtProxyActivity`: mở link, tạo shortcut, hoặc deep link sang YouTube.
- Hoàn thiện handler nút `Tạo lối tắt`.
- Validate link trước khi lưu.

Tiêu chí hoàn tất:

- Nhập link hợp lệ và bấm nút có hành vi nhìn thấy được.
- Link sai không crash.

### P2 - Test coverage

- Thêm unit test/source test cho receiver, service decisions, repository mapping.
- Thêm instrumentation smoke test cho MainActivity nếu môi trường cho phép.
- Viết test riêng cho route boot/ACC -> sound id.

Tiêu chí hoàn tất:

- Có test cho `ACC_ON` phát sound 1.
- Có test hoặc checklist xác nhận `ACC_OFF` không được quảng bá như luồng phát tạm biệt bắt buộc.
- Có test cho autoPlay disabled không phát.
- Có test cho token null khiến `CoreService` dừng.

## 5. Chiến lược test

### Local validation

- Build debug.
- Chạy unit tests nếu test source được thêm.
- Kiểm tra Manifest bằng source review.
- Kiểm tra Markdown links sau mỗi lần sửa docs.

### Device smoke test

- Install APK.
- Mở app.
- Login hoặc skip test mode.
- Chọn sound 1 và sound 2.
- Bật Auto Play.
- Bật Floating Button và cấp quyền overlay.
- Bấm từng nút trên overlay để phát/dừng.
- Gửi broadcast giả lập cho boot/ACC nếu thiết bị cho phép.

### Real car/head-unit test

- Cold boot.
- Sleep/wake.
- ACC ON.
- ACC OFF nếu thiết bị còn phát được broadcast trước khi ngủ, chỉ để ghi nhận hành vi thiết bị.
- Mất mạng rồi có mạng lại.
- Tắt/mở tối ưu pin.
- Thu hồi overlay permission rồi bật lại.

## 6. Bản đồ regression

| Vùng thay đổi | Regression cần kiểm tra |
| --- | --- |
| Manifest/permissions | App còn cài được, service không bị Android 14 chặn, Google Play risk không tăng âm thầm. |
| Boot receiver | Không mất route boot/quick boot/ACC. |
| CoreService | Không tạo nhiều heartbeat loop, không chạy khi logout, không crash khi thiếu token. |
| FloatingButtonService | Overlay không crash, kéo thả ổn, tắt switch remove view. |
| SoundPlayerService | Không leak MediaPlayer, không phát chồng, stop đúng sound đang chạy. |
| DataStore | URI và flags không mất sau restart. |
| NetworkModule/API | Login, ping, check-update không trỏ sai môi trường. |
| DownloadWorker | Retry đúng khi lỗi mạng, không ghi file hỏng. |
| Docs | README, PLAN, RELEASE_GATE và docs chi tiết không mô tả sai code. |

## 7. Tiêu chí chấp nhận dựa trên source

Một thay đổi được coi là đủ điều kiện merge khi:

- Code build được.
- Không thay đổi quyền Android nếu chưa cập nhật release gate.
- Luồng chính trong `docs/MAIN_FLOW.md` khớp code.
- Tài liệu nói rõ phần nào đã có, phần nào là backlog.
- Nếu chạm service/background, đã có smoke test trên thiết bị gần thực tế nhất.
- Nếu chạm Google Play sensitive permission, đã có note trong `RELEASE_GATE.md`.

## 8. Khoảng trống đã biết

- Source local chưa là git repository.
- GitHub repo đích có layout khác source local nếu không đồng bộ toàn bộ mã nguồn.
- Login đang demo, chưa production-ready.
- DownloadWorker chưa sync thật.
- Chưa có audio focus.
- Chưa có cơ chế refresh token.
- Chưa có QA matrix thiết bị thực tế.
- Chưa có automation test trong workspace local.

## 9. Bảo trì tài liệu

Cập nhật tài liệu khi:

- Thêm hoặc xóa permission trong Manifest.
- Đổi danh sách boot/ACC action.
- Đổi chu kỳ heartbeat.
- Đổi base URL hoặc API contract.
- Hoàn thiện login thật.
- Hoàn thiện download worker.
- Thêm test hoặc release process mới.

Quy tắc: tài liệu không được mô tả feature như đã hoàn tất nếu source chỉ mới có placeholder.
