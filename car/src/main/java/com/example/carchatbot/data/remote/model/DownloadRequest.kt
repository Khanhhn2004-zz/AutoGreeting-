package com.example.carchatbot.data.remote.model

import kotlinx.serialization.Serializable

@Serializable
data class DownloadRequest(
    val userId: String,
    val type: String
)
