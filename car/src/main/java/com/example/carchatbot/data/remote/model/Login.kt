package com.example.carchatbot.data.remote.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val phoneNumber: String,
    val password: String
)

@Serializable
data class LoginResponseLegacy(
    val userId: String
)
