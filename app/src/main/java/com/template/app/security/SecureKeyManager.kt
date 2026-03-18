package com.template.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.template.app.AppConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Secure key-value store backed by [EncryptedSharedPreferences].
 *
 * Uses Android's [MasterKey] (AES-256-GCM, stored in the Android Keystore) to encrypt
 * both preference keys (AES-256-SIV) and values (AES-256-GCM) on disk. The encrypted
 * file lives in the app's private data directory and is inaccessible without the device's
 * Keystore-bound master key.
 *
 * ## What to store here
 * - API keys and tokens entered by the user in settings (e.g. the GitHub personal access token)
 * - Any short secret string that must survive app restarts but must not be stored in plaintext
 *
 * ## What NOT to store here
 * - Large blobs (use [EncryptionHelper] for file encryption instead)
 * - Non-sensitive user preferences (use DataStore / [logging.DebugSettings])
 *
 * ## Well-known keys
 * - [bugreport.GitHubIssuesClient.KEY_GITHUB_TOKEN] — GitHub PAT for submitting issues
 * - [update.AppUpdateManager.KEY_GITHUB_TOKEN] — same token used for checking/downloading updates
 *
 * ## Lazy initialization
 * Both the [MasterKey] and the [EncryptedSharedPreferences] are initialized lazily on first
 * access to avoid blocking the main thread at injection time.
 *
 * @param context Application context used to create the Keystore key and locate the prefs file.
 * @param prefsFilename On-disk filename for the encrypted SharedPreferences file.
 *                      Defaults to [AppConfig.SECURE_PREFS_FILENAME]. Changing this after
 *                      shipping causes existing users to lose their stored keys.
 */
class SecureKeyManager(
    private val context: Context,
    private val prefsFilename: String = AppConfig.SECURE_PREFS_FILENAME
) {
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            prefsFilename,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Stores [value] under [alias], encrypted on disk. Overwrites any existing value.
     * Uses `commit()` (synchronous write) to guarantee the value is persisted before returning.
     */
    fun saveKey(alias: String, value: String) {
        encryptedPrefs.edit().putString(alias, value).commit()
    }

    /**
     * Returns the decrypted value for [alias], or `null` if no value has been stored.
     */
    fun getKey(alias: String): String? {
        return encryptedPrefs.getString(alias, null)
    }

    /**
     * Removes the entry for [alias] from the encrypted store.
     * No-op if [alias] does not exist.
     */
    fun deleteKey(alias: String) {
        encryptedPrefs.edit().remove(alias).commit()
    }

    /**
     * Returns `true` if a value is stored under [alias], `false` otherwise.
     * Does not decrypt the value — safe to call as a quick existence check.
     */
    fun hasKey(alias: String): Boolean {
        return encryptedPrefs.contains(alias)
    }

    companion object
}

/**
 * Hilt module that provides [SecureKeyManager] as an application-scoped singleton.
 * Uses [AppConfig.SECURE_PREFS_FILENAME] as the encrypted prefs filename.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecureKeyManagerModule {

    @Provides
    @Singleton
    fun provideSecureKeyManager(
        @ApplicationContext context: Context
    ): SecureKeyManager {
        return SecureKeyManager(context)
    }
}
