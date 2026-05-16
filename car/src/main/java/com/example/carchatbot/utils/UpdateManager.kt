package com.example.carchatbot.utils

import android.content.Context
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

object UpdateManager {

    internal fun prioritizedDirectories(
        filesDir: File?,
        externalFilesDir: File?,
        cacheDir: File?,
        externalCacheDir: File?
    ): List<File> {
        return listOfNotNull(
            filesDir,
            externalFilesDir,
            cacheDir,
            externalCacheDir
        ).distinctBy { it.absolutePath }
    }

    fun saveToDisk(responseBody: ResponseBody, context: Context, filename: String = "update.apk", onProgress: (Int) -> Unit = {}): File? {
        val directories = prioritizedDirectories(
            filesDir = context.filesDir,
            externalFilesDir = context.getExternalFilesDir(null),
            cacheDir = context.cacheDir,
            externalCacheDir = context.externalCacheDir
        )

        for (directory in directories) {
            try {
                if (!directory.exists() && !directory.mkdirs()) {
                    android.util.Log.w("UpdateManager", "Could not create directory: ${directory.absolutePath}")
                    continue
                }
                if (!directory.canWrite()) {
                    android.util.Log.w("UpdateManager", "Directory not writable: ${directory.absolutePath}")
                    continue
                }

                val file = File(directory, filename)
                android.util.Log.d("UpdateManager", "Attempting to save to: ${file.absolutePath}")
                
                 var inputStream: InputStream? = null
                 var outputStream: FileOutputStream? = null

                 try {
                     inputStream = responseBody.byteStream()
                     outputStream = FileOutputStream(file)
                     
                    val fileSize = responseBody.contentLength()
                    val data = ByteArray(4096)
                    var count: Int
                    var total: Long = 0
                    
                    while (inputStream.read(data).also { count = it } != -1) {
                        total += count.toLong()
                        outputStream.write(data, 0, count)
                        if (fileSize > 0) {
                            onProgress((total * 100 / fileSize).toInt())
                        }
                    }
                    outputStream.flush()
                    android.util.Log.d("UpdateManager", "File saved successfully: ${file.absolutePath}")
                    return file

                 } catch (e: IOException) {
                     android.util.Log.e("UpdateManager", "Failed to write to ${file.absolutePath}, trying next directory", e)
                     if (file.exists()) file.delete()
                     continue 
                 } finally {
                     inputStream?.close()
                     outputStream?.close()
                 }

            } catch (e: Exception) {
                android.util.Log.e("UpdateManager", "Unexpected error processing directory", e)
            }
        }
        
        android.util.Log.e("UpdateManager", "All save attempts failed")
        return null
    }
}
