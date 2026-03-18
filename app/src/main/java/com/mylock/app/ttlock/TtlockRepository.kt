package com.mylock.app.ttlock

import com.mylock.app.logging.AppLogger
import com.mylock.app.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for TTLock state.
 * Handles token lifecycle (store, refresh) and exposes high-level lock operations.
 */
@Singleton
class TtlockRepository @Inject constructor(
    private val client: TtlockClient,
    private val secureKeyManager: SecureKeyManager
) {
    companion object {
        private const val TAG = "TtlockRepository"
        const val KEY_ACCESS_TOKEN = "ttlock_access_token"
        const val KEY_REFRESH_TOKEN = "ttlock_refresh_token"
        const val KEY_TOKEN_EXPIRY = "ttlock_token_expiry"
        const val KEY_USERNAME = "ttlock_username"
        const val KEY_PASSWORD = "ttlock_password"
        const val KEY_SELECTED_LOCK_ID = "ttlock_selected_lock_id"
        const val KEY_SELECTED_LOCK_NAME = "ttlock_selected_lock_name"
    }

    // ── Credentials ──────────────────────────────────────────────────────────

    fun saveCredentials(username: String, password: String) {
        secureKeyManager.saveKey(KEY_USERNAME, username)
        secureKeyManager.saveKey(KEY_PASSWORD, password)
    }

    fun hasCredentials(): Boolean =
        secureKeyManager.hasKey(KEY_USERNAME) && secureKeyManager.hasKey(KEY_PASSWORD)

    fun clearSession() {
        secureKeyManager.deleteKey(KEY_ACCESS_TOKEN)
        secureKeyManager.deleteKey(KEY_REFRESH_TOKEN)
        secureKeyManager.deleteKey(KEY_TOKEN_EXPIRY)
    }

    // ── Token management ─────────────────────────────────────────────────────

    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val token = secureKeyManager.getKey(KEY_ACCESS_TOKEN) ?: return@withContext login()
        val expiry = secureKeyManager.getKey(KEY_TOKEN_EXPIRY)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() < expiry - 60_000) return@withContext token
        // Try refresh first, fall back to full login
        val refreshToken = secureKeyManager.getKey(KEY_REFRESH_TOKEN)
        if (refreshToken != null) {
            when (val r = client.refreshToken(refreshToken)) {
                is TtlockResult.Success -> {
                    saveToken(r.data)
                    return@withContext r.data.accessToken
                }
                is TtlockResult.Error -> AppLogger.w(TAG, "Refresh failed: ${r.message}")
            }
        }
        login()
    }

    private suspend fun login(): String? {
        val username = secureKeyManager.getKey(KEY_USERNAME) ?: return null
        val password = secureKeyManager.getKey(KEY_PASSWORD) ?: return null
        return when (val r = client.login(username, password)) {
            is TtlockResult.Success -> {
                if (r.data.errcode != 0) {
                    AppLogger.e(TAG, "Login errcode ${r.data.errcode}: ${r.data.errmsg}")
                    null
                } else {
                    saveToken(r.data)
                    r.data.accessToken
                }
            }
            is TtlockResult.Error -> {
                AppLogger.e(TAG, "Login failed: ${r.message}")
                null
            }
        }
    }

    private fun saveToken(t: TtlockTokenResponse) {
        secureKeyManager.saveKey(KEY_ACCESS_TOKEN, t.accessToken)
        secureKeyManager.saveKey(KEY_REFRESH_TOKEN, t.refreshToken)
        val expiry = System.currentTimeMillis() + t.expiresIn * 1000L
        secureKeyManager.saveKey(KEY_TOKEN_EXPIRY, expiry.toString())
    }

    // ── Lock selection ───────────────────────────────────────────────────────

    fun saveSelectedLock(lockId: Long, lockName: String) {
        secureKeyManager.saveKey(KEY_SELECTED_LOCK_ID, lockId.toString())
        secureKeyManager.saveKey(KEY_SELECTED_LOCK_NAME, lockName)
    }

    fun getSelectedLockId(): Long? =
        secureKeyManager.getKey(KEY_SELECTED_LOCK_ID)?.toLongOrNull()

    fun getSelectedLockName(): String? =
        secureKeyManager.getKey(KEY_SELECTED_LOCK_NAME)

    // ── Lock operations ──────────────────────────────────────────────────────

    suspend fun getLocks(): TtlockResult<List<TtlockLock>> = withContext(Dispatchers.IO) {
        val token = getValidAccessToken()
            ?: return@withContext TtlockResult.Error(-1, "Not authenticated")
        when (val r = client.getLocks(token)) {
            is TtlockResult.Success -> {
                if (r.data.errcode != 0)
                    TtlockResult.Error(r.data.errcode, r.data.errmsg)
                else
                    TtlockResult.Success(r.data.list)
            }
            is TtlockResult.Error -> r
        }
    }

    suspend fun unlock(lockId: Long): TtlockResult<Unit> = withContext(Dispatchers.IO) {
        val token = getValidAccessToken()
            ?: return@withContext TtlockResult.Error(-1, "Not authenticated")
        when (val r = client.unlock(token, lockId)) {
            is TtlockResult.Success -> {
                if (r.data.isSuccess) TtlockResult.Success(Unit)
                else TtlockResult.Error(r.data.errcode, r.data.errmsg)
            }
            is TtlockResult.Error -> r
        }
    }

    suspend fun lock(lockId: Long): TtlockResult<Unit> = withContext(Dispatchers.IO) {
        val token = getValidAccessToken()
            ?: return@withContext TtlockResult.Error(-1, "Not authenticated")
        when (val r = client.lock(token, lockId)) {
            is TtlockResult.Success -> {
                if (r.data.isSuccess) TtlockResult.Success(Unit)
                else TtlockResult.Error(r.data.errcode, r.data.errmsg)
            }
            is TtlockResult.Error -> r
        }
    }
}
