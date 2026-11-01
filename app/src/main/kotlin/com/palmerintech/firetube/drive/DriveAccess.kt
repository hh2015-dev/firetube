package com.palmerintech.firetube.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/**
 * Requests only read access. Google's AuthorizationClient holds grant state; this app never
 * writes OAuth tokens, authorization codes or secrets to disk or into URLs.
 *
 * Connecting to another Google account changes the namespace of offline files and catalogues.
 * No network request should ever run before the user grants the scope explicitly.
 */
class DriveAccess(private val context: Context, private val httpClient: OkHttpClient) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    }

    private val client get() = Identity.getAuthorizationClient(context)
    private val safeClient = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private var token: String? = null
    private var accountId: String? = null
    private val preferences = context.getSharedPreferences("firetube-drive-local-state", Context.MODE_PRIVATE)

    val connected: Boolean get() = token != null
    val namespace: String? get() = accountId ?: preferences.getString("account-hash", null)

    /** User action may return a consent PendingIntent; never launch it in the background. */
    suspend fun beginAuthorization(): AuthorizationResult =
        client.authorize(AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .build()).await()

    fun accept(result: AuthorizationResult) {
        if (result.hasResolution() || SCOPE !in result.grantedScopes) {
            throw IllegalStateException("Google Drive read-only access was not granted")
        }
        val access = result.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google did not return a Drive access token")
        // Drive authorization does not necessarily grant profile/email scopes, so both fields
        // can legitimately be null. Confirm the Drive permissionId via about.get instead.
        token = access
        accountId = null
    }

    fun acceptIntent(intent: Intent) = accept(client.getAuthorizationResultFromIntent(intent))

    /** Drive's stable permissionId identifies the granted account without extra identity scopes. */
    suspend fun confirmAccount(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/about?fields=user(permissionId)")
            .header("Authorization", "Bearer ${bearerToken()}")
            .build()
        val permissionId = safeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Drive account verification failed (HTTP ${response.code})")
            JSONObject(response.body.string()).getJSONObject("user").getString("permissionId")
        }
        if (permissionId.isBlank()) throw IOException("Google Drive account identifier is unavailable")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(permissionId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        accountId = hash
        preferences.edit().putString("account-hash", hash).apply()
        hash
    }

    suspend fun bearerToken(): String {
        token?.let { return it }
        val result = beginAuthorization()
        if (result.hasResolution()) throw IllegalStateException("Reconnect Google Drive to continue")
        accept(result)
        return requireNotNull(token)
    }

    /**
     * A failed token can be invalidated and re-authorized via Google's client cache.
     * Do not refresh continuously on 403 (which can mean a file cannot be downloaded).
     */
    suspend fun refreshToken(): String {
        token = null
        return bearerToken()
    }

    fun disconnectLocally() {
        token = null
        accountId = null
        preferences.edit().remove("account-hash").apply()
    }
}
