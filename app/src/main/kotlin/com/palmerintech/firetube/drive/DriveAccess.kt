package com.palmerintech.firetube.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/**
 * Requests only read access. Google's AuthorizationClient holds grant state; this app never
 * writes OAuth tokens, authorization codes or secrets to disk or into URLs.
 *
 * Connecting to another Google account changes the namespace of offline files and catalogues.
 * No network request should ever run before the user grants the scope explicitly.
 */
class DriveAccess(private val context: Context) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    }

    private val client get() = Identity.getAuthorizationClient(context)
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
        val identity = result.toGoogleSignInAccount()?.let { it.id ?: it.email }
            ?: throw IllegalStateException("Google account identity is unavailable")
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        accountId = hash
        token = access
        preferences.edit().putString("account-hash", hash).apply()
    }

    fun acceptIntent(intent: Intent) = accept(client.getAuthorizationResultFromIntent(intent))

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
