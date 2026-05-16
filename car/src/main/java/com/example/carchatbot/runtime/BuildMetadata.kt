package com.example.carchatbot.runtime

import com.example.carchatbot.BuildConfig

object BuildMetadata {
    fun displayName(): String = BuildConfig.APP_DISPLAY_NAME

    fun displayVersion(): String = BuildConfig.BUILD_VERSION_LABEL

    fun displayPublishedAt(): String = BuildConfig.BUILD_PUBLISHED_AT
}
