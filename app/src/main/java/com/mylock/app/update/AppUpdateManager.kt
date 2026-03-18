package com.mylock.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.mylock.app.BuildConfig
import com.mylock.app.logging.AppLogger
import com.mylock.app.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Metadata for an available update discovered by [AppUpdateManager.checkForUpdate].
 *
 * @property version The remote version string (e.g. `"1.2.3"`), with the leading `v` stripped.
 * @property downloadUrl The GitHub API asset URL for the APK. Must be downloaded with the
 *                       `Accept: application/octet-stream` header and a valid auth token.
 */
data class UpdateInfo(
    val version: String,
    val downloadUrl: String
)

/**
 * Manages over-the-air app updates distributed via GitHub Releases.
 *
 * ## Update flow
 * 1. Call [checkForUpdate] to fetch the latest release from the GitHub Releases API.
 *    Returns an [UpdateInfo] if the remote version is newer than [BuildConfig.VERSION_NAME],
 *    or `null` if already up-to-date or if an error occurs.
 * 2. Call [downloadApk] with the [UpdateInfo.downloadUrl] to download the APK to the cache.
 * 3. Call [createInstallIntent] with the downloaded [File] to get an [Intent] that launches
 *    the system package installer via [FileProvider]. Start the intent from your Activity.
 *
 * Alternatively, call [createBrowserDownloadIntent] to send the user to the GitHub releases
 * page in their browser instead of handling the download in-app.
 *
 * ## Authentication
 * A GitHub personal access token (PAT) is read from [SecureKeyManager] at call time using
 * [KEY_GITHUB_TOKEN]. The same token is used by [bugreport.GitHubIssuesClient], so the user
 * only needs to configure one token. If the token is absent, both [checkForUpdate] and
 * [downloadApk] return `null` with a warning log — no crash.
 *
 * Required PAT scope: `repo` (private) or `public_repo` (public).
 *
 * ## Version comparison
 * [isNewerVersion] compares dot-separated version strings numerically
 * (e.g. `"1.10.0"` > `"1.9.0"`). Non-numeric segments are treated as `0`.
 *
 * ## Manifest requirement
 * The `REQUEST_INSTALL_PACKAGES` permission is required in `AndroidManifest.xml` for the
 * install intent to work on Android 8+. This is already declared in the template.
 *
 * @param context Application context for file I/O and FileProvider URI generation.
 * @param secureKeyManager Used to retrieve the GitHub PAT.
 * @param repoOwner GitHub username or organization. Configured via [AppConfig.GITHUB_RELEASES_REPO_OWNER].
 * @param repoName GitHub repository name. Configured via [AppConfig.GITHUB_RELEASES_REPO_NAME].
 */
class AppUpdateManager(
    private val context: Context,
    private val secureKeyManager: SecureKeyManager,
    private val repoOwner: String,
    private val repoName: String
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Checks the GitHub Releases API for a newer version than the currently running build.
     *
     * Fetches `GET /repos/{owner}/{repo}/releases/latest`, parses the `tag_name`, and
     * compares it against [BuildConfig.VERSION_NAME] using [isNewerVersion]. If newer,
     * finds the first `.apk` asset and returns its API download URL.
     *
     * @return [UpdateInfo] if a newer version with an APK asset is available, `null` otherwise
     *         (including on network errors, missing token, or 404 responses).
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val token = secureKeyManager.getKey(KEY_GITHUB_TOKEN)
            if (token.isNullOrBlank()) {
                AppLogger.w(TAG, "No GitHub token configured - cannot check for updates")
                return@withContext null
            }

            val request = Request.Builder()
                .url("$GITHUB_API_URL/repos/$repoOwner/$repoName/releases/latest")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                AppLogger.e(TAG, "GitHub API error: ${response.code} - ${if (response.code == 404) "Repository not found or token invalid" else "Unknown error"}")
                return@withContext null
            }

            val json = JsonParser.parseString(body).asJsonObject
            val tagName = json.get("tag_name")?.asString ?: return@withContext null
            val remoteVersion = tagName.removePrefix("v")

            if (!isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) {
                return@withContext null
            }

            val assets = json.getAsJsonArray("assets")
            if (assets == null || assets.size() == 0) {
                AppLogger.e(TAG, "No assets in release")
                return@withContext null
            }

            var apkAssetUrl: String? = null
            for (asset in assets) {
                val assetObj = asset.asJsonObject
                val name = assetObj.get("name")?.asString ?: ""
                if (name.endsWith(".apk")) {
                    apkAssetUrl = assetObj.get("url")?.asString
                    break
                }
            }

            if (apkAssetUrl == null) {
                AppLogger.e(TAG, "No APK asset found in release")
                return@withContext null
            }

            AppLogger.i(TAG, "Update available: $remoteVersion (current: ${BuildConfig.VERSION_NAME})")
            UpdateInfo(version = remoteVersion, downloadUrl = apkAssetUrl)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check for update", e)
            null
        }
    }

    /**
     * Downloads the APK from [url] (a GitHub API asset URL) to the app's cache directory.
     *
     * Any previous files in `cache/updates/` are deleted before downloading to avoid
     * accumulating stale APKs. The file is saved as `{packageSuffix}-update.apk`.
     *
     * The download URL must use the GitHub API asset format (requires
     * `Accept: application/octet-stream` and a valid auth token).
     *
     * @param url The [UpdateInfo.downloadUrl] returned by [checkForUpdate].
     * @return The downloaded [File] in `cacheDir/updates/`, or `null` on failure.
     */
    suspend fun downloadApk(url: String): File? = withContext(Dispatchers.IO) {
        try {
            val token = secureKeyManager.getKey(KEY_GITHUB_TOKEN)
            if (token.isNullOrBlank()) {
                AppLogger.e(TAG, "No GitHub token configured - cannot download APK")
                return@withContext null
            }

            val updatesDir = File(context.cacheDir, "updates")
            if (updatesDir.exists()) {
                updatesDir.listFiles()?.forEach { it.delete() }
            }
            updatesDir.mkdirs()

            val appName = context.packageName.substringAfterLast('.')
            val apkFile = File(updatesDir, "$appName-update.apk")

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/octet-stream")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                AppLogger.e(TAG, "Download failed: ${response.code}")
                response.body?.close()
                return@withContext null
            }

            response.body?.byteStream()?.use { input ->
                apkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            AppLogger.i(TAG, "APK downloaded: ${apkFile.length()} bytes")
            apkFile
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to download APK", e)
            null
        }
    }

    /**
     * Creates an [Intent] that launches the system package installer for [apkFile].
     *
     * Uses [FileProvider] (authority `{packageName}.fileprovider`, declared in the manifest)
     * to expose the cache file to the installer without granting broad storage access.
     * The APK must be in `cache/updates/` as declared in `res/xml/file_paths.xml`.
     *
     * @param apkFile The downloaded APK file returned by [downloadApk].
     * @return An [Intent] ready to be passed to [Context.startActivity].
     */
    fun createInstallIntent(apkFile: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates an [Intent] that opens the GitHub releases page for this repo in the browser.
     * Use as a fallback when [downloadApk] fails or when you prefer not to handle in-app
     * installation.
     *
     * @return An [Intent] for `https://github.com/{owner}/{repo}/releases/latest`.
     */
    fun createBrowserDownloadIntent(): Intent {
        val url = "https://github.com/$repoOwner/$repoName/releases/latest"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Returns `true` if [remote] is a higher version number than [local].
     *
     * Comparison is done numerically, segment by segment on `.`-separated parts,
     * so `"1.10.0"` correctly ranks higher than `"1.9.0"`. Missing segments default to `0`.
     * Non-numeric segments are also treated as `0`.
     *
     * @param remote The remote version string (e.g. `"1.2.3"`), without leading `v`.
     * @param local The local version string from [BuildConfig.VERSION_NAME].
     */
    fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val GITHUB_API_URL = "https://api.github.com"

        /**
         * Key used with [SecureKeyManager] to retrieve the GitHub PAT for API requests.
         * Same key as [bugreport.GitHubIssuesClient.KEY_GITHUB_TOKEN] — both features share
         * one token so the user only needs to configure it once in settings.
         */
        const val KEY_GITHUB_TOKEN = "github_token"
    }
}
