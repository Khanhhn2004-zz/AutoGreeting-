package com.example.carchatbot.data.remote

import com.example.carchatbot.data.remote.model.DownloadRequest
import com.example.carchatbot.data.remote.model.IotStatus
import com.example.carchatbot.data.remote.model.LoginRequest
import com.example.carchatbot.data.remote.model.LoginResponse
import com.example.carchatbot.data.remote.model.PingResponse
import com.example.carchatbot.data.remote.model.DeviceInfoRequest
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Header
import retrofit2.http.Url

interface IotApiService {
    @GET
    suspend fun getStatus(@Url url: String): List<IotStatus>

    @POST("v2/device/login")
    suspend fun login(@Body loginRequest: LoginRequest): LoginResponse

    @POST("v2/device/ping")
    suspend fun ping(
        @Header("Authorization") token: String,
        @Body logs: com.example.carchatbot.data.remote.model.AppLogBatchRequest? = null
    ): PingResponse

    @Streaming
    @POST("v2/device/download")
    suspend fun downloadSound(
        @Header("Authorization") token: String,
        @Body request: DownloadRequest
    ): retrofit2.Response<ResponseBody>

    @POST("device")
    suspend fun pushDeviceInfo(
        @Header("Authorization") token: String,
        @Body deviceInfo: DeviceInfoRequest
    )
}
