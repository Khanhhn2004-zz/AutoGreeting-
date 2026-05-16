package com.example.carchatbot

import com.example.carchatbot.utils.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class UpdateManagerTest {

    @Test
    fun `persistent directories are preferred before cache directories`() {
        val filesDir = File("files")
        val externalFilesDir = File("external-files")
        val cacheDir = File("cache")
        val externalCacheDir = File("external-cache")

        assertEquals(
            listOf(filesDir, externalFilesDir, cacheDir, externalCacheDir),
            UpdateManager.prioritizedDirectories(
                filesDir = filesDir,
                externalFilesDir = externalFilesDir,
                cacheDir = cacheDir,
                externalCacheDir = externalCacheDir
            )
        )
    }

    @Test
    fun `duplicate directory entries are removed while keeping first occurrence`() {
        val sharedDir = File("shared")
        val cacheDir = File("cache")

        assertEquals(
            listOf(sharedDir, cacheDir),
            UpdateManager.prioritizedDirectories(
                filesDir = sharedDir,
                externalFilesDir = sharedDir,
                cacheDir = cacheDir,
                externalCacheDir = cacheDir
            )
        )
    }
}
