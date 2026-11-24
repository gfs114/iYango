package com.gfs.iyango.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gfs.iyango.core.domain.GradeCheckOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.gradeNotifyDataStore: DataStore<Preferences> by preferencesDataStore(name = "grade_notify_state")

@Serializable
data class GradeNotifyLocalState(
    val lastCheckAt: Long = 0L,
    val lastOutcome: String = GradeCheckOutcome.IDLE.name,
    val lastNotifiedCount: Int = 0,
    val lastError: String = "",
    val pausedBySessionExpired: Boolean = false,
    val notifiedKeysByAccount: Map<String, List<String>> = emptyMap(),
) {
    fun outcomeEnum(): GradeCheckOutcome =
        runCatching { GradeCheckOutcome.valueOf(lastOutcome) }.getOrDefault(GradeCheckOutcome.IDLE)

    fun keysForAccount(accountKey: String): Set<String> =
        notifiedKeysByAccount[accountKey].orEmpty().toSet()
}

/**
 * 成绩通知的本机状态与去重指纹存储（独立于跨设备设置备份）。
 */
@Singleton
class GradeNotifiedStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.gradeNotifyDataStore)

    private object Keys {
        val STATE_JSON = stringPreferencesKey("state_json")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val state: Flow<GradeNotifyLocalState> = dataStore.data.map { prefs ->
        decode(prefs[Keys.STATE_JSON])
    }

    suspend fun current(): GradeNotifyLocalState = state.first()

    suspend fun recordCheckResult(
        checkAt: Long,
        outcome: GradeCheckOutcome,
        notifiedCount: Int,
        error: String = "",
        pausedBySessionExpired: Boolean? = null,
        accountKeyForNewKeys: String? = null,
        newNotifiedKeys: Collection<String> = emptyList(),
    ) {
        dataStore.edit { prefs ->
            val current = decode(prefs[Keys.STATE_JSON])
            val updatedMap = current.notifiedKeysByAccount.toMutableMap()
            if (!accountKeyForNewKeys.isNullOrBlank() && newNotifiedKeys.isNotEmpty()) {
                val existing = updatedMap[accountKeyForNewKeys].orEmpty()
                val merged = (existing + newNotifiedKeys).distinct().let { list ->
                    if (list.size > MAX_KEYS_PER_ACCOUNT) list.takeLast(MAX_KEYS_PER_ACCOUNT) else list
                }
                updatedMap[accountKeyForNewKeys] = merged
            }
            val next = current.copy(
                lastCheckAt = checkAt,
                lastOutcome = outcome.name,
                lastNotifiedCount = notifiedCount,
                lastError = error,
                pausedBySessionExpired = pausedBySessionExpired ?: current.pausedBySessionExpired,
                notifiedKeysByAccount = updatedMap,
            )
            prefs[Keys.STATE_JSON] = json.encodeToString(GradeNotifyLocalState.serializer(), next)
        }
    }

    suspend fun clearSessionExpiredPause() {
        dataStore.edit { prefs ->
            val current = decode(prefs[Keys.STATE_JSON])
            if (!current.pausedBySessionExpired) return@edit
            val next = current.copy(pausedBySessionExpired = false, lastError = "")
            prefs[Keys.STATE_JSON] = json.encodeToString(GradeNotifyLocalState.serializer(), next)
        }
    }

    suspend fun clearAccount(accountKey: String) {
        if (accountKey.isBlank()) return
        dataStore.edit { prefs ->
            val current = decode(prefs[Keys.STATE_JSON])
            if (accountKey !in current.notifiedKeysByAccount) return@edit
            val next = current.copy(
                notifiedKeysByAccount = current.notifiedKeysByAccount - accountKey,
            )
            prefs[Keys.STATE_JSON] = json.encodeToString(GradeNotifyLocalState.serializer(), next)
        }
    }

    private fun decode(raw: String?): GradeNotifyLocalState {
        if (raw.isNullOrBlank()) return GradeNotifyLocalState()
        return runCatching {
            json.decodeFromString(GradeNotifyLocalState.serializer(), raw)
        }.getOrDefault(GradeNotifyLocalState())
    }

    companion object {
        const val MAX_KEYS_PER_ACCOUNT = 200
    }
}
