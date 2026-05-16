package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class PlayPermissionRemovalSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `manifest removes bluetooth location and package install permissions`() {
        val manifest = readSource("src/main/AndroidManifest.xml")

        assertFalse(manifest.contains("android.permission.BLUETOOTH_CONNECT"))
        assertFalse(manifest.contains("android.permission.BLUETOOTH_ADMIN"))
        assertFalse(manifest.contains("android.permission.BLUETOOTH\""))
        assertFalse(manifest.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(manifest.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertFalse(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertFalse(manifest.contains("com.example.carchatbot.service.BluetoothReceiver"))
        assertFalse(manifest.contains("android.bluetooth.device.action.ACL_CONNECTED"))
    }

    @Test
    fun `app source removes bluetooth autoplay selection and receiver logic`() {
        val mainViewModel = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")
        val mainScreen = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")
        val coreService = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")
        val userPrefs = readSource("src/main/java/com/example/carchatbot/data/local/UserPreferencesRepository.kt")
        val bluetoothReceiverFile = Paths.get("src/main/java/com/example/carchatbot/service/BluetoothReceiver.kt")

        assertFalse(mainViewModel.contains("Bluetooth"))
        assertFalse(mainViewModel.contains("bluetoothSoundEnabled"))
        assertFalse(mainViewModel.contains("selectedBluetoothDeviceAddress"))
        assertFalse(mainScreen.contains("setBluetoothSoundEnabled("))
        assertFalse(mainScreen.contains("setSelectedBluetoothDeviceAddress("))
        assertFalse(coreService.contains("ACTION_PLAY_BLUETOOTH_SOUND"))
        assertFalse(coreService.contains("EXTRA_BT_MAC_ADDRESS"))
        assertFalse(coreService.contains("AudioDeviceCallback"))
        assertFalse(userPrefs.contains("BLUETOOTH_SOUND_ENABLED_KEY"))
        assertFalse(userPrefs.contains("SELECTED_BLUETOOTH_DEVICE_ADDRESS_KEY"))
        assertFalse(Files.exists(bluetoothReceiverFile))
    }

    @Test
    fun `app source removes location checks and unknown sources install flow`() {
        val permissionScreen = readSource("src/main/java/com/example/carchatbot/ui/permission/PermissionScreen.kt")
        val deviceUtils = readSource("src/main/java/com/example/carchatbot/utils/DeviceUtils.kt")
        val updateManager = readSource("src/main/java/com/example/carchatbot/utils/UpdateManager.kt")
        val mainActivity = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")
        val coreService = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")
        val repository = readSource("src/main/java/com/example/carchatbot/data/repository/IotStatusRepository.kt")
        val apiService = readSource("src/main/java/com/example/carchatbot/data/remote/IotApiService.kt")

        assertFalse(permissionScreen.contains("canRequestPackageInstalls()"))
        assertFalse(permissionScreen.contains("ACTION_MANAGE_UNKNOWN_APP_SOURCES"))
        assertFalse(permissionScreen.contains("Cài đặt ứng dụng ngoài"))
        assertFalse(deviceUtils.contains("ACCESS_FINE_LOCATION"))
        assertFalse(deviceUtils.contains("ACCESS_COARSE_LOCATION"))
        assertFalse(updateManager.contains("installApk("))
        assertFalse(updateManager.contains("application/vnd.android.package-archive"))
        assertFalse(mainActivity.contains("UpdateManager.installApk"))
        assertFalse(mainActivity.contains("UpdateState"))
        assertFalse(mainActivity.contains("ACTION_CHECK_FOR_UPDATE"))
        assertFalse(mainActivity.contains("ACTION_START_UPDATE"))
        assertFalse(coreService.contains("checkForUpdate()"))
        assertFalse(coreService.contains("performUpdate("))
        assertFalse(coreService.contains("ACTION_UPDATE_AVAILABLE"))
        assertFalse(repository.contains("suspend fun checkUpdate()"))
        assertFalse(repository.contains("suspend fun downloadApp("))
        assertFalse(apiService.contains("suspend fun checkUpdate("))
        assertFalse(apiService.contains("suspend fun downloadApp("))
        assertFalse(apiService.contains("suspend fun downloadFile("))
    }
}
