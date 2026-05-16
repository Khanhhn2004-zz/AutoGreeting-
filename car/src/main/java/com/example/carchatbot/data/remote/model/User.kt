package com.example.carchatbot.data.remote.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val phoneNumber: String,
    val name: String
)
