package com.trustnet.vshield.parenting

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class ParentingPrefsData(
    val parentingEnabled: Boolean,
    val passwordHashBase64: String?,
    val passwordSaltBase64: String?
) {
    val hasPassword: Boolean
        get() = !passwordHashBase64.isNullOrBlank() && !passwordSaltBase64.isNullOrBlank()
}

private val Context.parentingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "parenting_prefs"
)

class ParentingPrefs(private val context: Context) {

    private object Keys {
        val parentingEnabled = booleanPreferencesKey("parentingEnabled")
        val passwordHash = stringPreferencesKey("passwordHash")
        val passwordSalt = stringPreferencesKey("passwordSalt")
    }

    val data: Flow<ParentingPrefsData> =
        context.parentingDataStore.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { prefs ->
                ParentingPrefsData(
                    parentingEnabled = prefs[Keys.parentingEnabled] ?: false,
                    passwordHashBase64 = prefs[Keys.passwordHash],
                    passwordSaltBase64 = prefs[Keys.passwordSalt],
                )
            }

    suspend fun setParentingEnabled(enabled: Boolean) {
        context.parentingDataStore.edit { prefs ->
            prefs[Keys.parentingEnabled] = enabled
        }
    }

    suspend fun setPassword(hashBase64: String, saltBase64: String) {
        context.parentingDataStore.edit { prefs ->
            prefs[Keys.passwordHash] = hashBase64
            prefs[Keys.passwordSalt] = saltBase64
        }
    }

    suspend fun clearPassword() {
        context.parentingDataStore.edit { prefs ->
            prefs.remove(Keys.passwordHash)
            prefs.remove(Keys.passwordSalt)
        }
    }
}