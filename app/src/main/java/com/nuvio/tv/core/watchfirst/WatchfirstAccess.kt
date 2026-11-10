package com.nuvio.tv.core.watchfirst

import com.nuvio.tv.domain.model.Addon
import android.content.Context
import android.util.Log
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Same service and origin the WATCHFIRST Windows and phone apps use. */
const val WATCHFIRST_ORIGIN = "https://membros.watchfirst.com.br"

private const val TAG = "WatchfirstAccess"
private const val LIFETIME = 253402300799000L
private const val PREFS = "watchfirst_access"
private const val KEY_TOKEN = "session_token"
private const val KEY_DEVICE = "device_id"
private const val KEY_NAME = "name"
private const val KEY_EMAIL = "email"
private const val KEY_PLAN = "plan"
private const val KEY_LIVE_ADDONS = "live_addons"
private const val KEY_FAVORITE_CHANNELS = "favorite_channels"
private val GATEWAY_URL = Regex("^https://[a-z0-9-]+\\.watchfirst\\.com\\.br/gateway/")
private val TOKEN_FORMAT = Regex("[a-f0-9]{48}")

data class WatchfirstMember(
    val id: String,
    val name: String,
    val plan: String,
    val expiresAt: Long,
    val lifetime: Boolean,
)

data class WatchfirstAddon(
    val id: String,
    val name: String,
    val kind: String,
    val content: String,
    val url: String,
)

data class WatchfirstAccessState(
    /** A session token exists on this device. Content is reachable only through it. */
    val granted: Boolean,
    val member: WatchfirstMember? = null,
    val addons: List<WatchfirstAddon> = emptyList(),
    /** Shown on the login screen after the service ends the session (blocked, expired, removed). */
    val notice: String? = null,
    /** Last known plan ("basic" or "max"), kept on the device so it is known before the first check. */
    val plan: String = "",
    /** Gateway URLs of the addons the administrator marked as live TV (MAX only). */
    val liveAddonUrls: Set<String> = emptySet(),
) {
    /** The "TV ao vivo" section: MAX members only, as on the PC and phone. */
    val liveTvEnabled: Boolean get() = granted && plan == "max"
}

class WatchfirstAccessException(message: String) : Exception(message)

/**
 * The live-TV addons of the current plan, by URL key. Live channels belong only in the "TV ao vivo" section,
 * so Home, Search/Discover and the catalog settings leave these addons out (same rule as the PC and phone).
 */
object WatchfirstLiveTv {
    internal val keys = MutableStateFlow<Set<String>>(emptySet())
    val liveKeys: StateFlow<Set<String>> = keys.asStateFlow()
}

/** These addons without the live-TV ones. */
fun List<Addon>.withoutWatchfirstLiveTv(live: Set<String> = WatchfirstLiveTv.liveKeys.value): List<Addon> =
    if (live.isEmpty()) this else filterNot { watchfirstAddonUrlKey(it.baseUrl) in live }

/** Stored addon URLs may gain "/manifest.json", a slash or a query; compare them by this key. */
fun watchfirstAddonUrlKey(url: String): String =
    url.trim().substringBefore("#").substringBefore("?").trimEnd('/')
        .removeSuffix("/manifest.json").trimEnd('/').lowercase()

/**
 * WATCHFIRST access for the TV app. Mirrors the Windows and phone apps: name and e-mail, then the
 * single-use code generated on the member site (or an owner-issued access code). The service returns a
 * session token; the member's addons arrive as gateway URLs bound to that token and are kept in sync with
 * the active profile.
 */
@OptIn(FlowPreview::class)
@Singleton
class WatchfirstAccess @Inject constructor(
    @ApplicationContext context: Context,
    private val okHttpClient: OkHttpClient,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val profileManager: ProfileManager,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(
        WatchfirstAccessState(
            granted = token() != null,
            plan = prefs.getString(KEY_PLAN, null).orEmpty(),
            liveAddonUrls = prefs.getString(KEY_LIVE_ADDONS, null).toLineSet(),
        ),
    )
    val state: StateFlow<WatchfirstAccessState> = _state.asStateFlow()

    private fun publish(next: WatchfirstAccessState) {
        WatchfirstLiveTv.keys.value = next.liveAddonUrls.mapTo(HashSet(), ::watchfirstAddonUrlKey)
        _state.value = next
    }

    val savedName: String get() = prefs.getString(KEY_NAME, null).orEmpty()
    val savedEmail: String get() = prefs.getString(KEY_EMAIL, null).orEmpty()

    init {
        WatchfirstLiveTv.keys.value = _state.value.liveAddonUrls.mapTo(HashSet(), ::watchfirstAddonUrlKey)
        // Keep the active profile's addons in line with the plan whenever the plan or the profile changes.
        scope.launch {
            combine(_state, profileManager.activeProfileId) { access, profileId ->
                Triple(access.granted, access.addons, profileId)
            }
                .distinctUntilChanged()
                .debounce(800)
                .collect { syncContent() }
        }
    }

    fun isLiveAddon(baseUrl: String): Boolean {
        val live = _state.value.liveAddonUrls
        return live.isNotEmpty() && watchfirstAddonUrlKey(baseUrl) in live.mapTo(HashSet(), ::watchfirstAddonUrlKey)
    }

    fun loadFavoriteChannels(): Set<String> = prefs.getString(KEY_FAVORITE_CHANNELS, null).toLineSet()

    fun saveFavoriteChannels(keys: Set<String>) {
        prefs.edit().putString(KEY_FAVORITE_CHANNELS, keys.joinToString("\n").ifEmpty { null }).apply()
    }

    /** Connects with the code from the member site (WF-XXXX-XXXX) or an owner-issued access code. */
    suspend fun connect(name: String, email: String, rawCode: String) {
        val cleanName = name.trim()
        val cleanEmail = email.trim().lowercase()
        val code = rawCode.trim().uppercase()
        prefs.edit().putString(KEY_NAME, cleanName).putString(KEY_EMAIL, cleanEmail).apply()
        val accessCode = Regex("^WF-?([A-F0-9]{48})$").matchEntire(code)
        if (accessCode != null) {
            val data = call(
                method = "POST",
                path = "/api/session",
                body = buildJsonObject {
                    put("code", "WF-" + accessCode.groupValues[1])
                    put("deviceId", deviceId())
                    put("deviceName", "WATCHFIRST TV")
                },
            )
            val token = data.string("token")
            if (!token.matches(TOKEN_FORMAT)) throw WatchfirstAccessException("O serviço de acesso não respondeu. Tente novamente.")
            prefs.edit().putString(KEY_TOKEN, token).apply()
            apply(data)
            return
        }
        val data = call(
            method = "POST",
            path = "/api/approval/link",
            body = buildJsonObject {
                put("code", code)
                put("name", cleanName)
                put("email", cleanEmail)
                put("deviceId", deviceId())
            },
        )
        val token = data.string("token")
        if (!token.matches(TOKEN_FORMAT)) throw WatchfirstAccessException("O serviço de acesso não respondeu. Tente novamente.")
        prefs.edit().putString(KEY_TOKEN, token).apply()
        apply(call("GET", "/api/session", token = token))
    }

    /** Ends the session on the service and on this device. */
    suspend fun signOut() {
        token()?.let { runCatching { call("DELETE", "/api/session", token = it) } }
        lockLocally(notice = null)
    }

    /** Checks the stored session now: refreshes plan and addons, or locks when the service ended it. */
    suspend fun refreshNow() {
        val token = token() ?: return
        try {
            apply(call("GET", "/api/session", token = token))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: HttpFailure) {
            // Only the service saying the session is over locks the app; network trouble keeps access.
            if (failure.status == 401 || failure.status == 403) lockLocally(notice = failure.message)
        } catch (failure: Exception) {
            Log.w(TAG, "Access check failed; keeping the current session", failure)
        }
    }

    /**
     * Installs the gateway URLs the plan grants on the active profile and removes WATCHFIRST gateway URLs that
     * are no longer granted. Addons and plugins that are not WATCHFIRST gateway URLs are never touched.
     */
    suspend fun syncContent() = syncLock.withLock {
        val desired = _state.value.let { if (it.granted) it.addons else emptyList() }
        runCatching {
            val wanted = desired.filter { it.kind == "stremio" }.map { it.url }
            val wantedKeys = wanted.mapTo(HashSet(), ::watchfirstAddonUrlKey)
            val installed = addonRepository.getInstalledAddons().first().map { it.baseUrl }
            val installedKeys = installed.mapTo(HashSet(), ::watchfirstAddonUrlKey)
            installed.filter { GATEWAY_URL.containsMatchIn(it) && watchfirstAddonUrlKey(it) !in wantedKeys }
                .forEach { addonRepository.removeAddon(it) }
            wanted.filter { watchfirstAddonUrlKey(it) !in installedKeys }
                .forEach { addonRepository.addAddon(it) }
        }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Addon sync failed", it) }
        runCatching {
            val wanted = desired.filter { it.kind == "plugin" }.map { it.url }
            val wantedKeys = wanted.mapTo(HashSet(), ::watchfirstAddonUrlKey)
            val installed = pluginManager.repositories.first()
            val installedKeys = installed.mapTo(HashSet()) { watchfirstAddonUrlKey(it.url) }
            installed.filter { GATEWAY_URL.containsMatchIn(it.url) && watchfirstAddonUrlKey(it.url) !in wantedKeys }
                .forEach { pluginManager.removeRepository(it.id) }
            wanted.filter { watchfirstAddonUrlKey(it) !in installedKeys }
                .forEach { pluginManager.addRepository(it) }
        }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Plugin sync failed", it) }
        Unit
    }

    private fun apply(data: JsonObject) {
        val m = data["member"]?.jsonObject ?: throw WatchfirstAccessException("O serviço de acesso não respondeu. Tente novamente.")
        val expiresAt = m["expiresAt"]?.jsonPrimitive?.longOrNull ?: 0L
        val member = WatchfirstMember(
            id = m.string("id"),
            name = m.string("name"),
            plan = m.string("plan"),
            expiresAt = expiresAt,
            lifetime = m["lifetime"]?.jsonPrimitive?.booleanOrNull == true || expiresAt == LIFETIME,
        )
        val addons = data["addons"]?.jsonArray.orEmpty().map { element ->
            val a = element.jsonObject
            WatchfirstAddon(a.string("id"), a.string("name"), a.string("kind"), a.string("content"), a.string("url"))
        }.filter { it.url.startsWith("https://") && (it.content != "live" || member.plan == "max") }
        val liveUrls = addons.filter { it.kind == "stremio" && it.content == "live" }.mapTo(LinkedHashSet()) { it.url }
        // Checked every minute: only touch storage when something changed.
        val liveValue = liveUrls.joinToString("\n").ifEmpty { null }
        if (prefs.getString(KEY_PLAN, null) != member.plan || prefs.getString(KEY_LIVE_ADDONS, null) != liveValue) {
            prefs.edit().putString(KEY_PLAN, member.plan).putString(KEY_LIVE_ADDONS, liveValue).apply()
        }
        publish(WatchfirstAccessState(
            granted = true,
            member = member,
            addons = addons,
            plan = member.plan,
            liveAddonUrls = liveUrls,
        ))
    }

    private fun lockLocally(notice: String?) {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_PLAN).remove(KEY_LIVE_ADDONS).apply()
        publish(WatchfirstAccessState(granted = false, notice = notice))
    }

    private fun token(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    private fun deviceId(): String = prefs.getString(KEY_DEVICE, null)
        ?: ("androidtv-" + UUID.randomUUID()).also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    private class HttpFailure(val status: Int, message: String) : Exception(message)

    private suspend fun call(
        method: String,
        path: String,
        body: JsonObject? = null,
        token: String? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(WATCHFIRST_ORIGIN + path)
            .header("Accept", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        val (status, text) = try {
            okHttpClient.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            throw WatchfirstAccessException("Sem conexão com o WATCHFIRST. Verifique a internet e tente de novo.")
        }
        val data = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (status !in 200..299) {
            val message = data?.string("error")?.ifBlank { null }
                ?: "Não foi possível validar o acesso (erro $status). Tente novamente."
            throw HttpFailure(status, message)
        }
        data ?: throw WatchfirstAccessException("O serviço de acesso não respondeu. Tente novamente.")
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    private fun String?.toLineSet(): Set<String> =
        this?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toCollection(LinkedHashSet()).orEmpty()
}
