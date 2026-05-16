package com.example.carchatbot.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("userId") val userId: String? = null,
    @SerialName("accessToken") val accessToken: String? = null
)

@Serializable
data class PingResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("message") val message: String
)

@Serializable
data class UpdateCheckResponse(
    @SerialName("hasUpdate") val hasUpdate: Boolean,
    @SerialName("latestVersion") val latestVersion: String? = null,
    @SerialName("downloadUrl") val downloadUrl: String? = null,
    @SerialName("appName") val appName: String? = null,
    @SerialName("createdAt") val createdAt: String? = null
)

@Serializable
data class DeviceInfoRequest(
    @SerialName("brand") val brand: String,
    @SerialName("model") val model: String,
    @SerialName("androidVersion") val androidVersion: String,
    @SerialName("sdkVersion") val sdkVersion: Int,
    @SerialName("screenResolution") val screenResolution: String,
    @SerialName("dpi") val dpi: Int,
    @SerialName("batteryLevel") val batteryLevel: Int,
    @SerialName("isRooted") val isRooted: Boolean,
    @SerialName("networkType") val networkType: String,
    @SerialName("internalIp") val internalIp: String,
    @SerialName("gpsLocation") val gpsLocation: GpsLocation?,
    @SerialName("appVersion") val appVersion: String,
    @SerialName("totalRam") val totalRam: String? = null,
    @SerialName("freeStorage") val freeStorage: String? = null,
    @SerialName("cpuArch") val cpuArch: String? = null,
    @SerialName("timezone") val timezone: String? = null,
    @SerialName("carrier") val carrier: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("appUpdateTime") val appUpdateTime: String? = null,
    @SerialName("installTime") val installTime: String? = null,
    @SerialName("internetStatus") val internetStatus: String? = null,
    @SerialName("overlay") val overlay: Boolean = false,
    @SerialName("notification") val notification: Boolean = false,
    @SerialName("battery_optimization") val batteryOptimization: Boolean = false,
    @SerialName("accessibility") val accessibility: Boolean = false,
    @SerialName("location") val location: Boolean = false
)

@Serializable
data class GpsLocation(
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
    @SerialName("address") val address: String
)
