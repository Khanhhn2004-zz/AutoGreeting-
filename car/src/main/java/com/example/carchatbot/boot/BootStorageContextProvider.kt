package com.example.carchatbot.boot

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cung cấp storage có thể đọc trong giai đoạn BOOT rất sớm.
 *
 * BOOT cache, arm state và playback ownership không được phụ thuộc thời điểm
 * credential unlock. Provider ưu tiên device-protected storage và chỉ fallback
 * về app context khi platform không cung cấp được context đó.
 */
@Singleton
class BootStorageContextProvider @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    /**
     * Trả về context phù hợp để đọc/ghi dữ liệu cần có trong giai đoạn boot sớm.
     */
    fun startupContext(): Context {
        val deviceProtectedContext = appContext.createDeviceProtectedStorageContext()
        return if (deviceProtectedContext.isDeviceProtectedStorage) {
            deviceProtectedContext
        } else {
            appContext
        }
    }

    /**
     * Tạo và trả về thư mục con trong startup storage.
     */
    fun startupStorageDir(directoryName: String): File {
        return File(startupContext().filesDir, directoryName).apply { mkdirs() }
    }
}
