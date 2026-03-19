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
        const val KEY_CLIENT_ID = "ttlock_client_id"
        const val KEY_CLIENT_SECRET = "ttlock_client_secret"
    }

    init {
        // Apply any previously saved developer credentials over the AppConfig defaults
        val storedId = secureKeyManager.getKey(KEY_CLIENT_ID)
        val storedSecret = secureKeyManager.getKey(KEY_CLIENT_SECRET)
        if (storedId != null && storedSecret != null) {
            client.clientId = storedId
            client.clientSecret = storedSecret
            AppLogger.i(TAG, "Loaded stored developer credentials (clientId=$storedId)")
        }
    }

    // ── Developer credentials (client_id / client_secret) ────────────────────

    fun hasClientCredentials(): Boolean =
        secureKeyManager.hasKey(KEY_CLIENT_ID) && secureKeyManager.hasKey(KEY_CLIENT_SECRET)

    fun saveClientCredentials(clientId: String, clientSecret: String) {
        secureKeyManager.saveKey(KEY_CLIENT_ID, clientId)
        secureKeyManager.saveKey(KEY_CLIENT_SECRET, clientSecret)
        client.clientId = clientId
        client.clientSecret = clientSecret
        clearSession() // force re-login with new credentials
        AppLogger.i(TAG, "Developer credentials updated (clientId=$clientId)")
    }

    // ── User credentials ──────────────────────────────────────────────────────

    fun saveCredentials(username: String, password: String) {
        secureKeyManager.saveKey(KEY_USERNAME, username)
        secureKeyManager.saveKey(KEY_PASSWORD, password)
    }

    /** Attempts login with the supplied credentials. Saves them (and the token) only on success. */
    suspend fun validateAndSaveCredentials(username: String, password: String): TtlockResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                AppLogger.i(TAG, "validateAndSaveCredentials: validating $username")
                when (val r = client.login(username, password)) {
                    is TtlockResult.Success -> {
                        if (r.data.errcode != 0) {
                            AppLogger.e(TAG, "validateAndSaveCredentials: API error ${r.data.errcode}: ${r.data.errmsg}")
                            val message = if (r.data.errcode == 10001)
                                "Invalid app credentials (client ID / secret). Go to Settings → Developer Credentials and enter valid TTLock open-platform credentials."
                            else
                                r.data.errmsg
                            TtlockResult.Error(r.data.errcode, message)
                        } else {
                            saveCredentials(username, password)
                            saveToken(r.data)
                            AppLogger.i(TAG, "validateAndSaveCredentials: success for $username")
                            TtlockResult.Success(Unit)
                        }
                    }
                    is TtlockResult.Error -> {
                        AppLogger.e(TAG, "validateAndSaveCredentials: error: ${r.message}")
                        r
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "validateAndSaveCredentials: unexpected exception: ${e.message}", e)
                TtlockResult.Error(-1, e.message ?: "Unknown error")
            }
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
        try {
            val token = secureKeyManager.getKey(KEY_ACCESS_TOKEN)
            if (token == null) {
                AppLogger.i(TAG, "getValidAccessToken: no cached token, logging in")
                return@withContext login()
            }
            val expiry = secureKeyManager.getKey(KEY_TOKEN_EXPIRY)?.toLongOrNull() ?: 0L
            val remaining = expiry - System.currentTimeMillis()
            if (remaining > 60_000) {
                AppLogger.i(TAG, "getValidAccessToken: cached token valid (${remaining / 1000}s left)")
                return@withContext token
            }
            AppLogger.i(TAG, "getValidAccessToken: token expired, attempting refresh")
            val refreshToken = secureKeyManager.getKey(KEY_REFRESH_TOKEN)
            if (refreshToken != null) {
                when (val r = client.refreshToken(refreshToken)) {
                    is TtlockResult.Success -> {
                        AppLogger.i(TAG, "getValidAccessToken: refresh succeeded")
                        saveToken(r.data)
                        return@withContext r.data.accessToken
                    }
                    is TtlockResult.Error -> AppLogger.w(TAG, "getValidAccessToken: refresh failed (${r.message}), falling back to login")
                }
            } else {
                AppLogger.w(TAG, "getValidAccessToken: no refresh token, falling back to login")
            }
            login()
        } catch (e: Exception) {
            AppLogger.e(TAG, "getValidAccessToken: unexpected exception: ${e.message}", e)
            null
        }
    }

    private suspend fun login(): String? {
        return try {
            val username = secureKeyManager.getKey(KEY_USERNAME)
            val password = secureKeyManager.getKey(KEY_PASSWORD)
            if (username == null || password == null) {
                AppLogger.w(TAG, "login: no stored credentials")
                return null
            }
            AppLogger.i(TAG, "login: attempting login for $username")
            when (val r = client.login(username, password)) {
                is TtlockResult.Success -> {
                    if (r.data.errcode != 0) {
                        AppLogger.e(TAG, "login: API error ${r.data.errcode}: ${r.data.errmsg}")
                        null
                    } else {
                        AppLogger.i(TAG, "login: success, token expires in ${r.data.expiresIn}s")
                        saveToken(r.data)
                        r.data.accessToken
                    }
                }
                is TtlockResult.Error -> {
                    AppLogger.e(TAG, "login: request error: ${r.message}")
                    null
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "login: unexpected exception: ${e.message}", e)
            null
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
        try {
            AppLogger.i(TAG, "getLocks: fetching lock list")
            val token = getValidAccessToken()
            if (token == null) {
                AppLogger.e(TAG, "getLocks: not authenticated")
                return@withContext TtlockResult.Error(-1, "Not authenticated")
            }
            when (val r = client.getLocks(token)) {
                is TtlockResult.Success -> {
                    if (r.data.errcode != 0) {
                        AppLogger.e(TAG, "getLocks: API error ${r.data.errcode}: ${r.data.errmsg}")
                        TtlockResult.Error(r.data.errcode, r.data.errmsg)
                    } else {
                        AppLogger.i(TAG, "getLocks: got ${r.data.list.size} locks")
                        TtlockResult.Success(r.data.list)
                    }
                }
                is TtlockResult.Error -> {
                    AppLogger.e(TAG, "getLocks: error: ${r.message}")
                    r
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "getLocks: unexpected exception: ${e.message}", e)
            TtlockResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun unlock(lockId: Long): TtlockResult<Unit> = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "unlock: lockId=$lockId")
            val token = getValidAccessToken()
            if (token == null) {
                AppLogger.e(TAG, "unlock: not authenticated")
                return@withContext TtlockResult.Error(-1, "Not authenticated")
            }
            when (val r = client.unlock(token, lockId)) {
                is TtlockResult.Success -> {
                    if (r.data.isSuccess) {
                        AppLogger.i(TAG, "unlock: success")
                        TtlockResult.Success(Unit)
                    } else {
                        AppLogger.e(TAG, "unlock: API error ${r.data.errcode}: ${r.data.errmsg}")
                        TtlockResult.Error(r.data.errcode, r.data.errmsg)
                    }
                }
                is TtlockResult.Error -> {
                    AppLogger.e(TAG, "unlock: error: ${r.message}")
                    r
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "unlock: unexpected exception: ${e.message}", e)
            TtlockResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun lock(lockId: Long): TtlockResult<Unit> = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "lock: lockId=$lockId")
            val token = getValidAccessToken()
            if (token == null) {
                AppLogger.e(TAG, "lock: not authenticated")
                return@withContext TtlockResult.Error(-1, "Not authenticated")
            }
            when (val r = client.lock(token, lockId)) {
                is TtlockResult.Success -> {
                    if (r.data.isSuccess) {
                        AppLogger.i(TAG, "lock: success")
                        TtlockResult.Success(Unit)
                    } else {
                        AppLogger.e(TAG, "lock: API error ${r.data.errcode}: ${r.data.errmsg}")
                        TtlockResult.Error(r.data.errcode, r.data.errmsg)
                    }
                }
                is TtlockResult.Error -> {
                    AppLogger.e(TAG, "lock: error: ${r.message}")
                    r
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "lock: unexpected exception: ${e.message}", e)
            TtlockResult.Error(-1, e.message ?: "Unknown error")
        }
    }
}
