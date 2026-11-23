package com.personalsecretary.app.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.personalsecretary.app.data.SecretaryVideoSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SecretaryMediaStore(private val context: Context) {

    private val videoDir: File by lazy {
        File(context.filesDir, "secretary_flow_videos").apply { mkdirs() }
    }

    private val modelsDir: File by lazy {
        val ext = context.getExternalFilesDir("models")
        (ext ?: File(context.filesDir, "models")).apply { mkdirs() }
    }

    /**
     * Copies a Google Flow generated MP4 into private, backup-excluded app storage
     * so playback remains reliable even if the gallery/download copy is moved.
     */
    suspend fun importGoogleFlowVideo(slot: SecretaryVideoSlot, sourceUri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val maxBytes = 250L * 1024L * 1024L // 250 MB safety cap for executive loops
                val displayName = queryDisplayName(sourceUri) ?: "${slot.slotKey}.mp4"
                val declaredSize = queryFileSize(sourceUri)
                if (declaredSize != null && declaredSize > maxBytes) {
                    throw IllegalArgumentException("Video exceeds 250 MB limit ($displayName). Please trim your Google Flow clip.")
                }

                val targetFile = File(videoDir, "flow_${slot.slotKey}.mp4")
                val tmpFile = File(videoDir, "flow_${slot.slotKey}.tmp")
                var totalBytes = 0L

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    tmpFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            totalBytes += read
                            if (totalBytes > maxBytes) {
                                throw IllegalArgumentException("Video exceeds 250 MB safety limit.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw IllegalStateException("Could not open selected video file.")

                if (totalBytes < 1024L) {
                    tmpFile.delete()
                    throw IllegalArgumentException("Selected video appears empty or unreadable.")
                }

                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                targetFile.absolutePath
            }
        }

    fun deleteSlotVideo(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            if (file.exists() && file.canonicalPath.startsWith(videoDir.canonicalPath)) {
                file.delete()
            }
        }
    }

    /**
     * Imports a user-selected `.litertlm` model into app-accessible storage so LiteRT-LM
     * can open it directly by absolute file path without SAF permission expiry.
     */
    suspend fun importLiteRtModel(
        sourceUri: Uri,
        onProgress: (copiedMb: Long, totalMb: Long?) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = (queryDisplayName(sourceUri) ?: "gemma-4-E4B-it.litertlm")
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            require(fileName.endsWith(".litertlm", ignoreCase = true) || fileName.endsWith(".task", ignoreCase = true)) {
                "Please select a .litertlm model file (e.g. gemma-4-E4B-it.litertlm)."
            }
            val declaredSize = queryFileSize(sourceUri)
            val totalMb = declaredSize?.let { it / (1024L * 1024L) }

            val targetFile = File(modelsDir, fileName)
            val tmpFile = File(modelsDir, "$fileName.importing")
            var copied = 0L
            var lastReportedMb = -1L

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val mb = copied / (1024L * 1024L)
                        if (mb != lastReportedMb && (mb % 25L == 0L || lastReportedMb < 0)) {
                            lastReportedMb = mb
                            onProgress(mb, totalMb)
                        }
                    }
                }
            } ?: throw IllegalStateException("Cannot read selected model file.")

            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            onProgress(copied / (1024L * 1024L), totalMb)
            targetFile.absolutePath
        }
    }

    fun scanKnownModelPaths(): List<File> {
        val candidates = mutableListOf<File>()
        val dirs = listOfNotNull(
            modelsDir,
            File(context.filesDir, "models"),
            File("/sdcard/Download"),
            File("/sdcard/Downloads"),
            File("/sdcard/Models"),
            File("/sdcard/Documents")
        )
        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory || !dir.canRead()) continue
            runCatching {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.canRead() && f.name.endsWith(".litertlm", ignoreCase = true)) {
                        candidates += f
                    }
                }
            }
        }
        return candidates.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    fun getAppModelsDirectory(): File = modelsDir

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun queryFileSize(uri: Uri): Long? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
    }
}
