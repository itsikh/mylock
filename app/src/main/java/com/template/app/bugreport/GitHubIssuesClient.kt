package com.template.app.bugreport

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.template.app.logging.AppLogger
import com.template.app.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Result of a [GitHubIssuesClient.createIssue] call.
 *
 * @property success `true` if the GitHub API returned 2xx and an issue was created.
 * @property issueUrl The HTML URL of the created issue (e.g. `https://github.com/owner/repo/issues/42`).
 *                    Only present when [success] is `true`.
 * @property error Human-readable error description. Only present when [success] is `false`.
 */
data class GitHubIssueResult(
    val success: Boolean,
    val issueUrl: String? = null,
    val error: String? = null
)

/**
 * HTTP client for the GitHub REST API v3, scoped to a single repository.
 *
 * Provides two operations used by [ui.screens.bugreport.BugReportViewModel]:
 * - [uploadScreenshot]: uploads a JPEG image as a file to the repo via the Contents API.
 * - [createIssue]: opens a new issue with the bug report body and optional labels.
 *
 * ## Authentication
 * A GitHub personal access token (PAT) is retrieved from [SecureKeyManager] at call time
 * using the key [KEY_GITHUB_TOKEN]. If the token is absent, both operations fail gracefully
 * with an error message rather than crashing. The user must configure the token in settings.
 *
 * Required PAT scopes:
 * - `repo` (for private repos) or `public_repo` (for public repos)
 *
 * ## Security — sensitive data sanitization
 * Before creating an issue, [sanitizeIssueContent] scans the body for patterns that look
 * like API keys (OpenAI, Gemini, GitHub tokens, Anthropic, generic Bearer tokens, JSON
 * password fields) and redacts them. This prevents accidental leakage of secrets that
 * might appear in log lines attached to the report.
 *
 * ## Screenshot upload
 * Screenshots are uploaded to `screenshots/bug_<timestamp>.jpg` in the repository via the
 * GitHub Contents API (base64-encoded PUT). The returned `download_url` is embedded as a
 * Markdown image in the issue body. If upload fails, the issue is still created without
 * the screenshot — it is never a blocking failure.
 *
 * @param secureKeyManager Used to retrieve the GitHub PAT at request time.
 * @param repoOwner GitHub username or organization. Configured via [AppConfig.GITHUB_ISSUES_REPO_OWNER].
 * @param repoName GitHub repository name. Configured via [AppConfig.GITHUB_ISSUES_REPO_NAME].
 */
class GitHubIssuesClient(
    private val secureKeyManager: SecureKeyManager,
    private val repoOwner: String,
    private val repoName: String
) {
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads a screenshot image to the GitHub repository via the Contents API.
     *
     * The file is stored at `screenshots/bug_<timestamp>.jpg` (or a custom [filename]).
     * Returns the `download_url` of the uploaded file, which is a public CDN URL suitable
     * for embedding in a Markdown `![alt](url)` image tag.
     *
     * Requires the GitHub token stored under [KEY_GITHUB_TOKEN] in [SecureKeyManager].
     *
     * @param imageBytes JPEG-compressed image bytes (see [ui.screens.bugreport.BugReportViewModel.compressScreenshot]).
     * @param filename Path within the repo where the file will be created.
     * @return The CDN download URL of the uploaded file, or `null` on any failure.
     */
    suspend fun uploadScreenshot(imageBytes: ByteArray, filename: String = "screenshots/bug_${System.currentTimeMillis()}.jpg"): String? {
        return withContext(Dispatchers.IO) {
            try {
                val token = secureKeyManager.getKey(KEY_GITHUB_TOKEN)
                    ?: return@withContext null

                val base64Content = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

                val body = mapOf(
                    "message" to "bug report screenshot",
                    "content" to base64Content
                )

                val jsonBody = gson.toJson(body)
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$GITHUB_API_URL/repos/$repoOwner/$repoName/contents/$filename")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .put(jsonBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JsonParser.parseString(responseBody).asJsonObject
                    val downloadUrl = json.getAsJsonObject("content")?.get("download_url")?.asString
                    AppLogger.i(TAG, "Screenshot uploaded: $downloadUrl")
                    downloadUrl
                } else {
                    AppLogger.e(TAG, "Failed to upload screenshot: ${response.code} $responseBody")
                    null
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to upload screenshot", e)
                null
            }
        }
    }

    /**
     * Creates a new GitHub issue in the configured repository.
     *
     * The [body] is sanitized by [sanitizeIssueContent] before submission to remove any
     * accidental API key patterns from attached log lines.
     *
     * Requires the GitHub token stored under [KEY_GITHUB_TOKEN] in [SecureKeyManager].
     *
     * @param title Issue title (shown in the GitHub issues list). Should be concise.
     * @param body Markdown-formatted issue body. Will be sanitized before sending.
     * @param labels List of label names to apply. Labels that don't exist in the repo
     *               will be silently ignored by GitHub.
     * @return [GitHubIssueResult] with [GitHubIssueResult.success] `true` and
     *         [GitHubIssueResult.issueUrl] set on success, or an error message on failure.
     */
    suspend fun createIssue(
        title: String,
        body: String,
        labels: List<String> = listOf("bug", "from-app")
    ): GitHubIssueResult {
        return withContext(Dispatchers.IO) {
            try {
                val token = secureKeyManager.getKey(KEY_GITHUB_TOKEN)
                    ?: return@withContext GitHubIssueResult(
                        success = false,
                        error = "GitHub Token not configured in settings"
                    )

                // Sanitize the body to prevent accidental key leakage
                val sanitizedBody = sanitizeIssueContent(body)
                if (sanitizedBody != body) {
                    AppLogger.w(TAG, "Issue body was sanitized - potential sensitive data detected")
                }

                val issueBody = mapOf(
                    "title" to title,
                    "body" to sanitizedBody,
                    "labels" to labels
                )

                val jsonBody = gson.toJson(issueBody)
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$GITHUB_API_URL/repos/$repoOwner/$repoName/issues")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .post(jsonBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JsonParser.parseString(responseBody).asJsonObject
                    val issueUrl = json.get("html_url")?.asString
                    AppLogger.i(TAG, "Bug report submitted: $issueUrl")
                    GitHubIssueResult(success = true, issueUrl = issueUrl)
                } else {
                    val errorMsg = "GitHub error: ${response.code}"
                    AppLogger.e(TAG, "Failed to create issue: ${response.code} $responseBody")
                    GitHubIssueResult(success = false, error = errorMsg)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to create GitHub issue", e)
                GitHubIssueResult(success = false, error = "Connection error: ${e.message}")
            }
        }
    }

    /**
     * Scans [content] for known sensitive data patterns and replaces matches with
     * redaction placeholders. Called automatically on the issue body before [createIssue]
     * sends it to GitHub.
     *
     * Patterns detected:
     * - OpenAI API keys (`sk-...`)
     * - Google/Gemini API keys (`AIza...`)
     * - GitHub personal access tokens (`ghp_...`, `gho_...`)
     * - Anthropic/Claude API keys (`sk-ant-...`)
     * - Generic Bearer authorization headers
     * - JSON fields named `api_key`, `apiKey`, `x-api-key`
     * - JSON fields named `password`
     */
    private fun sanitizeIssueContent(content: String): String {
        var sanitized = content

        val sensitivePatterns = listOf(
            // OpenAI API keys
            Regex("sk-[a-zA-Z0-9]{20,}", RegexOption.MULTILINE) to "[OPENAI_API_KEY_REDACTED]",
            // Google/Gemini API keys
            Regex("AIza[a-zA-Z0-9_-]{35}", RegexOption.MULTILINE) to "[GEMINI_API_KEY_REDACTED]",
            // GitHub tokens
            Regex("ghp_[a-zA-Z0-9]{36}", RegexOption.MULTILINE) to "[GITHUB_TOKEN_REDACTED]",
            Regex("gho_[a-zA-Z0-9]{36}", RegexOption.MULTILINE) to "[GITHUB_TOKEN_REDACTED]",
            // Anthropic/Claude API keys
            Regex("sk-ant-[a-zA-Z0-9_-]{95,}", RegexOption.MULTILINE) to "[CLAUDE_API_KEY_REDACTED]",
            // Generic bearer tokens
            Regex("Bearer\\s+[a-zA-Z0-9_-]{20,}", RegexOption.MULTILINE) to "Bearer [TOKEN_REDACTED]",
            // JSON API key patterns
            Regex("\"(?:api_?key|apiKey|x-api-key)\"\\s*:\\s*\"[^\"]{10,}\"", RegexOption.MULTILINE) to "\"apiKey\":\"[REDACTED]\"",
            // Password patterns in JSON
            Regex("\"password\"\\s*:\\s*\"[^\"]+\"", RegexOption.MULTILINE) to "\"password\":\"[REDACTED]\"",
        )

        for ((pattern, replacement) in sensitivePatterns) {
            sanitized = pattern.replace(sanitized, replacement)
        }

        return sanitized
    }

    companion object {
        private const val TAG = "GitHubIssuesClient"
        private const val GITHUB_API_URL = "https://api.github.com"

        /**
         * Key used with [SecureKeyManager] to store and retrieve the GitHub PAT.
         * The same key is referenced by [update.AppUpdateManager.KEY_GITHUB_TOKEN] —
         * both features share a single token so the user only needs to enter it once.
         */
        const val KEY_GITHUB_TOKEN = "github_token"
    }
}
