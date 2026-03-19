package com.mylock.app.ttlock

import com.google.gson.Gson
import com.mylock.app.logging.AppLogger
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the TTLock Cloud API v3.
 * Docs: https://euopen.ttlock.com/document/doc?urlPathParam=cloudApi
 *
 * Automatically detects whether the developer credentials belong to the EU
 * platform (euapi.ttlock.com) or the global platform (api.ttlock.com) by
 * trying the EU endpoint first. If the server returns "invalid client" the
 * client transparently retries against the global endpoint and remembers the
 * working URL for all subsequent calls.
 */
class TtlockClient(
    clientId: String,
    clientSecret: String
) {
    @Volatile var clientId: String = clientId
    @Volatile var clientSecret: String = clientSecret
    private val gson = Gson()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val EU_BASE_URL = "https://euapi.ttlock.com"
        private const val GLOBAL_BASE_URL = "https://api.ttlock.com"
        private const val TAG = "TtlockClient"
    }

    /** Resolved at runtime — starts with EU, switches to global on "invalid client". */
    @Volatile private var baseUrl = EU_BASE_URL

    // ── Auth ─────────────────────────────────────────────────────────────────

    suspend fun login(username: String, password: String): TtlockResult<TtlockTokenResponse> {
        AppLogger.i(TAG, "login() → $baseUrl | user=$username")
        val result = attemptLogin(username, password, baseUrl)

        // Auto-detect endpoint: if this platform doesn't know our client_id, try the other one
        if (result is TtlockResult.Success && isInvalidClient(result.data)) {
            val fallback = if (baseUrl == EU_BASE_URL) GLOBAL_BASE_URL else EU_BASE_URL
            AppLogger.w(TAG, "'invalid client' from $baseUrl — retrying against $fallback")
            val fallbackResult = attemptLogin(username, password, fallback)
            if (fallbackResult is TtlockResult.Success && !isInvalidClient(fallbackResult.data)) {
                AppLogger.i(TAG, "Endpoint auto-detected: switching permanently to $fallback")
                baseUrl = fallback
            }
            return fallbackResult
        }
        return result
    }

    private suspend fun attemptLogin(
        username: String,
        password: String,
        url: String
    ): TtlockResult<TtlockTokenResponse> {
        AppLogger.i(TAG, "attemptLogin() POST $url/oauth2/token | user=$username")
        return try {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", md5(clientSecret))
                .add("username", username)
                .add("password", md5(password))
                .build()
            val request = Request.Builder().url("$url/oauth2/token").post(body).build()
            execute(request) { gson.fromJson(it, TtlockTokenResponse::class.java) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "attemptLogin() exception: ${e.message}", e)
            TtlockResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    private fun isInvalidClient(r: TtlockTokenResponse) =
        r.errcode == 10001 || (r.errcode != 0 && r.errmsg.contains("invalid client", ignoreCase = true))

    suspend fun refreshToken(refreshToken: String): TtlockResult<TtlockTokenResponse> {
        AppLogger.i(TAG, "refreshToken() → $baseUrl")
        return try {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", md5(clientSecret))
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
            val request = Request.Builder().url("$baseUrl/oauth2/token").post(body).build()
            execute(request) { gson.fromJson(it, TtlockTokenResponse::class.java) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "refreshToken() exception: ${e.message}", e)
            TtlockResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    // ── Lock operations ───────────────────────────────────────────────────────

    suspend fun getLocks(accessToken: String, pageNo: Int = 1): TtlockResult<TtlockLockListResponse> {
        AppLogger.i(TAG, "getLocks() page=$pageNo → $baseUrl")
        return try {
            val url = "$baseUrl/v3/lock/list" +
                    "?clientId=$clientId" +
                    "&accessToken=$accessToken" +
                    "&pageNo=$pageNo" +
                    "&pageSize=20" +
                    "&date=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).get().build()
            execute(request) { gson.fromJson(it, TtlockLockListResponse::class.java) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "getLocks() exception: ${e.message}", e)
            TtlockResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun unlock(accessToken: String, lockId: Long): TtlockResult<TtlockCommandResponse> {
        AppLogger.i(TAG, "unlock() lockId=$lockId → $baseUrl")
        return try {
            val body = FormBody.Builder()
                .add("clientId", clientId)
                .add("accessToken", accessToken)
                .add("lockId", lockId.toString())
                .add("date", System.currentTimeMillis().toString())
                .build()
            val request = Request.Builder().url("$baseUrl/v3/lock/unlock").post(body).build()
            execute(request) { gson.fromJson(it, TtlockCommandResponse::class.java) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "unlock() exception: ${e.message}", e)
            TtlockResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun lock(accessToken: String, lockId: Long): TtlockResult<TtlockCommandResponse> {
        AppLogger.i(TAG, "lock() lockId=$lockId → $baseUrl")
        return try {
            val body = FormBody.Builder()
                .add("clientId", clientId)
                .add("accessToken", accessToken)
                .add("lockId", lockId.toString())
                .add("date", System.currentTimeMillis().toString())
                .build()
            val request = Request.Builder().url("$baseUrl/v3/lock/lock").post(body).build()
            execute(request) { gson.fromJson(it, TtlockCommandResponse::class.java) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "lock() exception: ${e.message}", e)
            TtlockResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun <T : Any> execute(
        request: Request,
        parse: (String) -> T
    ): TtlockResult<T> {
        return try {
            AppLogger.i(TAG, "→ ${request.method} ${request.url}")
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            AppLogger.i(TAG, "← HTTP ${response.code} [${body.length} chars]: $body")
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "HTTP error ${response.code}: $body")
                return TtlockResult.Error(response.code, "HTTP ${response.code}: $body")
            }
            val parsed = try {
                parse(body)
            } catch (e: Exception) {
                AppLogger.e(TAG, "JSON parse failed for body: $body | error: ${e.message}", e)
                return TtlockResult.Error(-1, "JSON parse error: ${e.message}")
            }
            TtlockResult.Success(parsed)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Request failed: ${e.message}", e)
            TtlockResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    private fun md5(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "MD5 failed: ${e.message}", e)
            input
        }
    }
}
