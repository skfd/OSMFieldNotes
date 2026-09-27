package com.surveyme.data

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.surveyme.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Who the app is acting as on osm.org. */
sealed class OsmAuthState {
    object LoggedOut : OsmAuthState()
    data class LoggedIn(val displayName: String, val uid: Long) : OsmAuthState()
}

/**
 * OSM login: OAuth 2.0 Authorization Code with PKCE (S256), public client, no secret.
 *
 * Authorize and token endpoints are `${BuildConfig.OSM_OAUTH_BASE_URL}/oauth2/authorize` and
 * `/oauth2/token` (verified against osm.org's `.well-known/oauth-authorization-server`, which
 * lists S256, `authorization_code` only, and no refresh tokens; osm.org tokens do not expire).
 * The authorize page opens in a Chrome Custom Tab and returns through the
 * `osmfieldnotes://oauth/callback` intent-filter on MainActivity, which hands the URI to
 * [handleCallback]. The PKCE verifier and `state` are kept in the encrypted prefs so the
 * exchange survives the process being killed behind the browser.
 *
 * Token, display name and uid live in EncryptedSharedPreferences (`osm_auth.xml`, excluded from
 * backup: the key is device-bound). One instance per process via [get], like [PoiManager].
 */
class OsmAuthManager private constructor(context: Context) {

    companion object {
        private const val PREFS_FILE = "osm_auth"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_NAME = "display_name"
        private const val KEY_UID = "uid"
        private const val KEY_PENDING_STATE = "pending_state"
        private const val KEY_PENDING_VERIFIER = "pending_verifier"

        /** `read_prefs` is what `user/details` needs; `write_notes` is what item 6 posts with. */
        const val SCOPES = "write_notes read_prefs"
        val REDIRECT_URI: String = BuildConfig.OSM_OAUTH_REDIRECT_URI
        val BASE_URL: String = BuildConfig.OSM_OAUTH_BASE_URL.trimEnd('/')

        @Volatile
        private var instance: OsmAuthManager? = null

        fun get(context: Context): OsmAuthManager =
            instance ?: synchronized(this) {
                instance ?: OsmAuthManager(context.applicationContext).also { instance = it }
            }

        /** True when [uri] is our redirect, whatever the outcome it carries. */
        fun isCallback(uri: Uri?): Boolean {
            if (uri == null) return false
            val expected = Uri.parse(REDIRECT_URI)
            return uri.scheme == expected.scheme && uri.host == expected.host && uri.path == expected.path
        }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = openPrefs(appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<OsmAuthState> = _state

    val isLoggedIn: Boolean get() = _state.value is OsmAuthState.LoggedIn

    /** False until the maintainer registers a client and puts its id in local.properties. */
    val isConfigured: Boolean get() = BuildConfig.OSM_OAUTH_CLIENT_ID.isNotBlank()

    /** `Bearer …` for API calls, or null when logged out. */
    fun authHeader(): String? = prefs.getString(KEY_TOKEN, null)?.let { "Bearer $it" }

    /**
     * Opens the osm.org authorize page. [LoginLaunch.NotConfigured] when there is no client id,
     * [LoginLaunch.NoBrowser] when nothing can open the URL; the caller shows the message.
     */
    fun login(activity: Activity): LoginLaunch {
        if (!isConfigured) return LoginLaunch.NotConfigured

        val verifier = randomUrlSafe(32)
        val state = randomUrlSafe(16)
        prefs.edit()
            .putString(KEY_PENDING_VERIFIER, verifier)
            .putString(KEY_PENDING_STATE, state)
            .apply()

        val url = Uri.parse("$BASE_URL/oauth2/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", BuildConfig.OSM_OAUTH_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", s256(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        Timber.d("Launching OSM authorize at $BASE_URL")

        return try {
            // Custom Tab when a provider exists; the intent is a plain ACTION_VIEW so any
            // browser answers it otherwise.
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, url)
            LoginLaunch.Launched
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No browser for OSM login")
            LoginLaunch.NoBrowser
        }
    }

    sealed class LoginLaunch {
        object Launched : LoginLaunch()
        object NotConfigured : LoginLaunch()
        object NoBrowser : LoginLaunch()
    }

    /**
     * Finishes the flow from the redirect URI: checks `state`, exchanges the code, fetches the
     * user's name and uid, stores everything. The pending verifier is cleared on entry so a
     * replayed intent cannot exchange twice.
     */
    suspend fun handleCallback(uri: Uri): Result<OsmAuthState.LoggedIn> = withContext(Dispatchers.IO) {
        val expectedState = prefs.getString(KEY_PENDING_STATE, null)
        val verifier = prefs.getString(KEY_PENDING_VERIFIER, null)
        prefs.edit().remove(KEY_PENDING_STATE).remove(KEY_PENDING_VERIFIER).apply()

        uri.getQueryParameter("error")?.let { error ->
            val description = uri.getQueryParameter("error_description")
            Timber.w("OSM login refused: $error $description")
            return@withContext Result.failure(
                IOException(if (error == "access_denied") "Login cancelled on osm.org" else description ?: error)
            )
        }
        val code = uri.getQueryParameter("code")
            ?: return@withContext Result.failure(IOException("No code in the OSM redirect"))
        if (expectedState == null || verifier == null) {
            return@withContext Result.failure(IOException("No login in progress; start again"))
        }
        if (uri.getQueryParameter("state") != expectedState) {
            Timber.w("OSM login state mismatch")
            return@withContext Result.failure(IOException("Login state mismatch; start again"))
        }

        runCatching {
            val token = exchangeCode(code, verifier)
            val (name, uid) = fetchUserDetails(token)
            prefs.edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_NAME, name)
                .putLong(KEY_UID, uid)
                .apply()
            val loggedIn = OsmAuthState.LoggedIn(name, uid)
            _state.value = loggedIn
            Timber.i("Logged in to OSM as $name ($uid)")
            loggedIn
        }.onFailure { Timber.e(it, "OSM login failed") }
    }

    /** Forgets the token locally and asks osm.org to revoke it (best effort). */
    suspend fun logout() {
        val token = prefs.getString(KEY_TOKEN, null)
        prefs.edit().remove(KEY_TOKEN).remove(KEY_NAME).remove(KEY_UID).apply()
        _state.value = OsmAuthState.LoggedOut
        if (token == null) return
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder()
                    .add("token", token)
                    .add("client_id", BuildConfig.OSM_OAUTH_CLIENT_ID)
                    .build()
                client.newCall(request("$BASE_URL/oauth2/revoke").post(body).build()).execute().use {
                    Timber.d("OSM token revoke: HTTP ${it.code}")
                }
            }.onFailure { Timber.w(it, "OSM token revoke failed; token dropped locally anyway") }
        }
    }

    private fun exchangeCode(code: String, verifier: String): String {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", BuildConfig.OSM_OAUTH_CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        client.newCall(request("$BASE_URL/oauth2/token").post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching { JSONObject(text).optString("error_description").ifBlank { null } }.getOrNull()
                throw IOException("Token exchange failed: HTTP ${response.code}${reason?.let { ", $it" } ?: ""}")
            }
            return JSONObject(text).optString("access_token").ifBlank {
                throw IOException("Token response had no access_token")
            }
        }
    }

    private fun fetchUserDetails(token: String): Pair<String, Long> {
        val req = request("$BASE_URL/api/0.6/user/details.json")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("user/details failed: HTTP ${response.code}")
            val user = JSONObject(response.body?.string().orEmpty()).optJSONObject("user")
                ?: throw IOException("user/details had no user object")
            val name = user.optString("display_name").ifBlank { throw IOException("user/details had no display_name") }
            return name to user.optLong("id", 0L)
        }
    }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "OSMFieldNotes/${BuildConfig.VERSION_NAME}")

    private fun readState(): OsmAuthState {
        val token = prefs.getString(KEY_TOKEN, null) ?: return OsmAuthState.LoggedOut
        if (token.isBlank()) return OsmAuthState.LoggedOut
        return OsmAuthState.LoggedIn(
            displayName = prefs.getString(KEY_NAME, null) ?: "OSM user",
            uid = prefs.getLong(KEY_UID, 0L)
        )
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Encrypted prefs, or a fresh file when the stored one cannot be decrypted (keystore reset,
     * restored from another device): the user logs in again rather than the app crashing.
     */
    private fun openPrefs(context: Context): SharedPreferences {
        fun open(): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return try {
            open()
        } catch (e: Exception) {
            Timber.e(e, "Encrypted prefs unreadable; starting a fresh file")
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.deleteSharedPreferences(PREFS_FILE)
                File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_FILE.xml").delete()
            }
            open()
        }
    }
}
