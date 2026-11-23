package com.personalsecretary.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val publishedAt: String,
    val apkName: String,
    val apkSizeBytes: Long,
    val browserDownloadUrl: String,
    val apiAssetUrl: String,
    val sha256AssetApiUrl: String? = null,
    val sha256BrowserUrl: String? = null,
    val expectedSha256: String? = null
)

data class OtaUpdateState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val availableRelease: GitHubReleaseInfo? = null,
    val downloadedApkPath: String? = null,
    val statusMessage: String? = null,
    val needsInstallPermission: Boolean = false
)

class OtaUpdateManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Used for private GitHub asset API call so we do not leak Authorization header to S3 redirects
    private val noRedirectClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val otaCacheDir: File by lazy {
        File(context.cacheDir, "ota_updates").apply { mkdirs() }
    }

    suspend fun checkForUpdate(
        repoSlug: String,
        currentVersionName: String,
        githubToken: String = ""
    ): Result<GitHubReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanRepo = repoSlug.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
            require(cleanRepo.contains("/") && cleanRepo.split("/").size == 2) {
                "Enter GitHub repo as owner/repo (e.g. Siroj-ai/personal-secretary)."
            }

            val url = "https://api.github.com/repos/$cleanRepo/releases/latest"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PersonalSecretary-Android")
            if (githubToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${githubToken.trim()}")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when (resp.code) {
                    404 -> throw IllegalStateException(
                        if (githubToken.isBlank()) {
                            "No published release found at $cleanRepo (if private, add a read-only GitHub token below)."
                        } else {
                            "No published release found yet at $cleanRepo."
                        }
                    )
                    401, 403 -> throw IllegalStateException("GitHub API access denied (HTTP ${resp.code}). Check token permissions or rate limit.")
                }
                if (!resp.isSuccessful) {
                    throw IllegalStateException("GitHub check failed (HTTP ${resp.code}).")
                }

                var release = parseGitHubReleaseJson(body)
                if (!isNewerVersion(release.versionName, currentVersionName)) {
                    return@runCatching null
                }

                // If checksum wasn't embedded in release notes, check optional .sha256 sidecar asset
                if (release.expectedSha256 == null && (release.sha256BrowserUrl != null || release.sha256AssetApiUrl != null)) {
                    val sidecarSha = fetchSidecarSha256(release, githubToken)
                    if (sidecarSha != null) {
                        release = release.copy(expectedSha256 = sidecarSha)
                    }
                }
                release
            }
        }
    }

    private fun fetchSidecarSha256(release: GitHubReleaseInfo, githubToken: String): String? {
        return runCatching {
            val bytes = downloadRawBytes(
                apiAssetUrl = release.sha256AssetApiUrl,
                browserUrl = release.sha256BrowserUrl,
                githubToken = githubToken
            )
            extractSha256FromText(bytes.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun downloadRawBytes(apiAssetUrl: String?, browserUrl: String?, githubToken: String): ByteArray {
        val (downloadUrl, useAuth) = resolveDownloadUrl(apiAssetUrl, browserUrl, githubToken)
        val req = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "PersonalSecretary-Android")
            .apply { if (useAuth) header("Authorization", "Bearer ${githubToken.trim()}") }
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Asset download HTTP ${resp.code}")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun downloadReleaseApk(
        release: GitHubReleaseInfo,
        githubToken: String = "",
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // Clear old cached APKs so cache stays small
            otaCacheDir.listFiles()?.forEach { it.delete() }

            val safeName = release.apkName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(otaCacheDir, safeName)
            val tmpFile = File(otaCacheDir, "$safeName.part")

            val (downloadUrl, useAuth) = resolveDownloadUrl(
                apiAssetUrl = release.apiAssetUrl,
                browserUrl = release.browserDownloadUrl,
                githubToken = githubToken
            )

            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "PersonalSecretary-Android")
                .apply {
                    if (useAuth) {
                        header("Authorization", "Bearer ${githubToken.trim()}")
                        header("Accept", "application/octet-stream")
                    }
                }
                .build()

            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("APK download failed (HTTP ${resp.code}).")
                }
                val body = resp.body ?: throw IllegalStateException("Empty APK response.")
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes

                var downloaded = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    tmpFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            digest.update(buf, 0, read)
                            downloaded += read
                            if (totalBytes > 0L) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                }
            }

            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = release.expectedSha256?.lowercase(Locale.US)
            if (expected != null && actualSha256 != expected) {
                tmpFile.delete()
                throw SecurityException("APK SHA-256 checksum mismatch! Expected $expected, got $actualSha256.")
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            onProgress(1f)
            targetFile
        }
    }

    /**
     * For private GitHub releases, requests the GitHub Asset API with `Accept: application/octet-stream`
     * without auto-following redirects so we get the temporary signed S3 URL and download without
     * forwarding the GitHub Bearer token to S3.
     */
    private fun resolveDownloadUrl(
        apiAssetUrl: String?,
        browserUrl: String?,
        githubToken: String
    ): Pair<String, Boolean> {
        if (githubToken.isNotBlank() && !apiAssetUrl.isNullOrBlank()) {
            val req = Request.Builder()
                .url(apiAssetUrl)
                .header("Authorization", "Bearer ${githubToken.trim()}")
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "PersonalSecretary-Android")
                .build()
            noRedirectClient.newCall(req).execute().use { resp ->
                val location = resp.header("Location")
                if (resp.code in 301..308 && !location.isNullOrBlank()) {
                    return location to false
                }
            }
            return apiAssetUrl to true
        }
        val direct = browserUrl?.takeIf { it.isNotBlank() }
            ?: apiAssetUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Release asset has no download URL.")
        return direct to false
    }

    fun canRequestPackageInstalls(): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    fun createUnknownSourcesSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun createInstallApkIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val shaRegex = Regex("(?i)\\b([a-f0-9]{64})\\b")

        fun normalizeVersion(raw: String): String {
            return raw.trim().removePrefix("v").removePrefix("V")
        }

        fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
            val remoteParts = parseSemverTuple(remoteTag)
            val currentParts = parseSemverTuple(currentVersion)
            val len = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until len) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseSemverTuple(raw: String): List<Int> {
            val core = normalizeVersion(raw).substringBefore("-").substringBefore("+")
            return core.split(".").map { seg ->
                seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
        }

        fun extractSha256FromText(text: String): String? {
            return shaRegex.find(text)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)
        }

        fun parseGitHubReleaseJson(rawJson: String): GitHubReleaseInfo {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val tagName = (root["tag_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val name = (root["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { tagName }
            val body = (root["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val publishedAt = (root["published_at"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val assets = (root["assets"] as? JsonArray) ?: JsonArray(emptyList())

            var apkObj: JsonObject? = null
            var shaObj: JsonObject? = null

            for (el in assets) {
                val obj = el as? JsonObject ?: continue
                val assetName = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                when {
                    assetName.endsWith(".apk", ignoreCase = true) && apkObj == null -> apkObj = obj
                    assetName.endsWith(".sha256", ignoreCase = true) && shaObj == null -> shaObj = obj
                }
            }

            val apk = apkObj ?: throw IllegalStateException("Latest GitHub release ($tagName) has no .apk asset attached.")
            return GitHubReleaseInfo(
                tagName = tagName,
                versionName = normalizeVersion(tagName),
                title = name,
                releaseNotes = body.ifBlank { "No release notes provided." },
                publishedAt = publishedAt,
                apkName = (apk["name"] as? JsonPrimitive)?.contentOrNull ?: "PersonalSecretary.apk",
                apkSizeBytes = (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
                browserDownloadUrl = (apk["browser_download_url"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                apiAssetUrl = (apk["url"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                sha256AssetApiUrl = (shaObj?.get("url") as? JsonPrimitive)?.contentOrNull,
                sha256BrowserUrl = (shaObj?.get("browser_download_url") as? JsonPrimitive)?.contentOrNull,
                expectedSha256 = extractSha256FromText(body)
            )
        }
    }
}
