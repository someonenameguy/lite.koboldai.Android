package net.koboldai.lite

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class WebAppInterface(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun saveBlobFile(base64Data: String, filename: String, mimeType: String) {
        executor.execute {
            try {
                val cleanBase64 = if (base64Data.contains(",")) {
                    base64Data.substringAfter(",")
                } else {
                    base64Data
                }
                val data = Base64.decode(cleanBase64, Base64.DEFAULT)
                val safeFilename = sanitizeFilename(filename)
                val safeMimeType = if (mimeType.isNotBlank()) mimeType else "application/octet-stream"

                saveDataToDownloads(safeFilename, safeMimeType, data)
                mainHandler.post {
                    Toast.makeText(context, "Saved to Downloads: $safeFilename", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save blob file", e)
                mainHandler.post {
                    Toast.makeText(context, "Failed to save file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun saveTextFile(content: String, filename: String, mimeType: String) {
        executor.execute {
            try {
                val data = content.toByteArray(Charsets.UTF_8)
                val safeFilename = sanitizeFilename(filename)
                val safeMimeType = if (mimeType.isNotBlank()) mimeType else "application/json"

                saveDataToDownloads(safeFilename, safeMimeType, data)
                mainHandler.post {
                    Toast.makeText(context, "Saved to Downloads: $safeFilename", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save text file", e)
                mainHandler.post {
                    Toast.makeText(context, "Failed to save file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveDataToDownloads(filename: String, mimeType: String, data: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("Failed to create download URI")
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(data)
                outputStream.flush()
            } ?: throw IllegalStateException("Failed to open output stream")
        } else {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            var targetFile = File(downloadDir, filename)
            var count = 1
            val baseName = targetFile.nameWithoutExtension
            val ext = if (targetFile.extension.isNotEmpty()) ".${targetFile.extension}" else ""
            while (targetFile.exists()) {
                targetFile = File(downloadDir, "${baseName}_$count$ext")
                count++
            }
            FileOutputStream(targetFile).use { outputStream ->
                outputStream.write(data)
                outputStream.flush()
            }
        }
    }

    private fun sanitizeFilename(filename: String): String {
        val trimmed = filename.trim().ifEmpty { "download_${System.currentTimeMillis()}" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    companion object {
        private const val TAG = "KoboldWebAppInterface"
    }
}
