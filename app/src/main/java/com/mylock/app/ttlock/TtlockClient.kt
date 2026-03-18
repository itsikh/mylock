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
 * Auth flow: POST /oauth2/token with client_id + md5(password) + username
 * Lock control: POST /v3/lock/unlock with access_token + lockId + date
 */
class TtlockClient(
    private val clientId: String,
    private val clientSecret: String
) {
    private val gson = Gson()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    companion object {
        private const val BASE_URL = "https://euapi.ttlock.com"
        private const val TAG = "TtlockClient"
    }

    suspend fun login(username: String, password: String): TtlockResult<TtlockTokenResponse> {
        val md5Password = md5(password)
        val md5ClientSecret = md5(clientSecret)

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", md5ClientSecret)
            .add("username", username)
            .add("password", md5Password)
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/oauth2/token")
            .post(body)
            .build()

        return execute(request) { gson.fromJson(it, TtlockTokenResponse::class.java) }
    }

    suspend fun refreshToken(refreshToken: String): TtlockResult<TtlockTokenResponse> {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", md5(clientSecret))
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/oauth2/token")
            .post(body)
            .build()

        return execute(request) { gson.fromJson(it, TtlockTokenResponse::class.java) }
    }

    suspend fun getLocks(accessToken: String, pageNo: Int = 1): TtlockResult<TtlockLockListResponse> {
        val url = "$BASE_URL/v3/lock/list" +
                "?clientId=$clientId" +
                "&accessToken=$accessToken" +
                "&pageNo=$pageNo" +
                "&pageSize=20" +
                "&date=${System.currentTimeMillis()}"

        val request = Request.Builder().url(url).get().build()
        return execute(request) { gson.fromJson(it, TtlockLockListResponse::class.java) }
    }

    suspend fun unlock(accessToken: String, lockId: Long): TtlockResult<TtlockCommandResponse> {
        val body = FormBody.Builder()
            .add("clientId", clientId)
            .add("accessToken", accessToken)
            .add("lockId", lockId.toString())
            .add("date", System.currentTimeMillis().toString())
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/v3/lock/unlock")
            .post(body)
            .build()

        return execute(request) { gson.fromJson(it, TtlockCommandResponse::class.java) }
    }

    suspend fun lock(accessToken: String, lockId: Long): TtlockResult<TtlockCommandResponse> {
        val body = FormBody.Builder()
            .add("clientId", clientId)
            .add("accessToken", accessToken)
            .add("lockId", lockId.toString())
            .add("date", System.currentTimeMillis().toString())
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/v3/lock/lock")
            .post(body)
            .build()

        return execute(request) { gson.fromJson(it, TtlockCommandResponse::class.java) }
    }

    private suspend fun <T : Any> execute(
        request: Request,
        parse: (String) -> T
    ): TtlockResult<T> {
        return try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            AppLogger.d(TAG, "Response ${request.url}: $body")
            val parsed = parse(body)
            // Check errcode via reflection-free approach — rely on caller checking result type
            TtlockResult.Success(parsed)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Request failed: ${e.message}", e)
            TtlockResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
