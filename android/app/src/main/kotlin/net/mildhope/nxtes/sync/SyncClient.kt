package net.mildhope.nxtes.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class Usage(val used: Long, val quota: Long)

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    @SerialName("created_at") val created: Long,
    @SerialName("last_seen") val lastSeen: Long,
    val current: Boolean = false,
)

/** A PDF anyone can view in a browser at [url]. */
@Serializable
data class ShareInfo(
    val id: String,
    val url: String,
    val title: String,
    val size: Long,
    val views: Long = 0,
    @SerialName("created_at") val created: Long,
    @SerialName("expires_at") val expires: Long? = null,
)

/**
 * The Nxtes server's HTTP API. Requests carry the account's access token, renewing it once when
 * the server refuses it, and wait and retry when the server says to slow down.
 */
class SyncClient(
    private val account: SyncAccount,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) : SyncService {
    private val refreshLock = Mutex()

    /** Creates an account; true when its address has to be confirmed before signing in. */
    suspend fun register(server: String, email: String, password: String): Boolean =
        request(server, "/v1/auth/register", "POST", json(Credentials(email, password))).read<Registered>().verificationRequired

    suspend fun login(server: String, email: String, password: String, deviceName: String): Session {
        val tokens = request(server, "/v1/auth/login", "POST", json(Credentials(email, password, deviceName))).read<Tokens>()
        val user = requireNotNull(tokens.user)
        return Session(normalize(server), user.email, user.id, tokens.deviceId, tokens.accessToken, tokens.refreshToken)
    }

    suspend fun resendVerification(server: String, email: String) =
        request(server, "/v1/auth/resend-verification", "POST", json(buildJsonObject { put("email", email) })).check()

    suspend fun requestPasswordReset(server: String, email: String) =
        request(server, "/v1/auth/password-reset", "POST", json(buildJsonObject { put("email", email) })).check()

    suspend fun usage(): Usage = authed("/v1/usage").read()

    suspend fun devices(): List<DeviceInfo> = authed("/v1/devices").read<DeviceList>().devices

    suspend fun removeDevice(id: String) = authed("/v1/devices/$id", "DELETE").check()

    /** Signs this device out on the server, which drops it from the device list. */
    suspend fun logout() = authed("/v1/auth/logout", "POST", EMPTY).check()

    suspend fun deleteAccount(password: String) = authed("/v1/account", "DELETE", json(buildJsonObject { put("password", password) })).check()

    /** Uploads a PDF for a web link that lasts [days], or until removed when null. */
    suspend fun createShare(file: File, title: String, days: Int?): ShareInfo {
        val query = "title=" + URLEncoder.encode(title, "UTF-8").replace("+", "%20") + (days?.let { "&days=$it" } ?: "")
        return authed("/v1/shares?$query", "POST", file.asRequestBody(PDF)).read()
    }

    suspend fun shares(): List<ShareInfo> = authed("/v1/shares").read<ShareList>().shares

    suspend fun removeShare(id: String) = authed("/v1/shares/$id", "DELETE").check()

    override suspend fun changes(since: Long): Changes = authed("/v1/changes?since=$since").read()

    override suspend fun missingBlobs(hashes: Collection<String>): List<String> =
        if (hashes.isEmpty()) emptyList() else authed("/v1/blobs/missing", "POST", json(Hashes(hashes.distinct()))).read<Missing>().missing

    override suspend fun putBlob(sha256: String, file: File) =
        authed("/v1/blobs/$sha256", "PUT", file.asRequestBody(OCTETS)).check()

    override suspend fun getBlob(sha256: String, into: File) {
        authed("/v1/blobs/$sha256").use { response ->
            if (!response.isSuccessful) throw failure(response.code, response.body.string())
            val digest = MessageDigest.getInstance("SHA-256")
            into.outputStream().use { out ->
                val input = response.body.byteStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != sha256) {
                into.delete()
                throw IOException("Blob $sha256 arrived damaged")
            }
        }
    }

    override suspend fun putItem(kind: String, id: String, baseRevision: Long, body: JsonElement): Long =
        authed("/v1/${kind}s/$id", "PUT", json(ItemWrite(baseRevision, body))).read<Written>().revision

    override suspend fun deleteItem(kind: String, id: String, baseRevision: Long): Long =
        authed("/v1/${kind}s/$id?base_revision=$baseRevision", "DELETE").read<Written>().revision

    /**
     * Listens for changes made on other devices until the connection ends, calling [onOpen] once
     * connected and [onChange] with each new change sequence.
     */
    suspend fun listen(onOpen: () -> Unit, onChange: (Long) -> Unit) {
        val session = account.session ?: return
        val ended = CompletableDeferred<Int?>()
        val request = Request.Builder()
            .url(normalize(session.server) + "/v1/events")
            .header("Authorization", "Bearer ${session.accessToken}")
            .build()
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) = onOpen()

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type == "change") data.trim().toLongOrNull()?.let(onChange)
            }

            override fun onClosed(eventSource: EventSource) {
                ended.complete(null)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                ended.complete(response?.code)
            }
        }
        val source = EventSources.createFactory(http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()).newEventSource(request, listener)
        try {
            if (ended.await() == 401) refresh(session)
        } finally {
            source.cancel()
        }
    }

    private suspend fun authed(path: String, method: String = "GET", body: RequestBody? = null): Response {
        val session = account.session ?: throw ApiException(401, "signed_out")
        val response = request(session.server, path, method, body, session.accessToken)
        if (response.code != 401) return response
        response.close()
        val fresh = refresh(session) ?: throw ApiException(401, "signed_out")
        return request(fresh.server, path, method, body, fresh.accessToken)
    }

    /** New tokens after [used] was refused; null, signing out, when the server won't renew them. */
    private suspend fun refresh(used: Session): Session? = refreshLock.withLock {
        val current = account.session ?: return@withLock null
        if (current.accessToken != used.accessToken) return@withLock current
        val response = request(current.server, "/v1/auth/refresh", "POST", json(RefreshRequest(current.refreshToken)))
        if (response.code == 401) {
            response.close()
            account.signOut(expired = true)
            return@withLock null
        }
        val tokens = response.read<Tokens>()
        current.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken).also(account::update)
    }

    private suspend fun request(server: String, path: String, method: String = "GET", body: RequestBody? = null, token: String? = null): Response {
        var wait = 1_000L
        repeat(ATTEMPTS) { attempt ->
            val request = Request.Builder()
                .url(normalize(server) + path)
                .method(method, body)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            val response = http.newCall(request).await()
            if (response.code != 429 || attempt == ATTEMPTS - 1) return response
            val after = response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: wait
            response.close()
            delay(after.coerceAtMost(60_000))
            wait *= 2
        }
        error("unreachable")
    }

    private inline fun <reified T> Response.read(): T = use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) throw failure(response.code, text)
        syncJson.decodeFromString<T>(text)
    }

    private fun Response.check() = use { response ->
        if (!response.isSuccessful) throw failure(response.code, response.body.string())
    }

    private fun failure(status: Int, text: String): ApiException {
        val details = runCatching { syncJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return ApiException(status, details["error"]?.jsonPrimitive?.contentOrNull ?: "http_$status", details)
    }

    private inline fun <reified T> json(value: T): RequestBody = syncJson.encodeToString(value).toRequestBody(JSON)

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        })
    }

    @Serializable
    private class Credentials(val email: String, val password: String, @SerialName("device_name") val deviceName: String? = null)

    @Serializable
    private class Registered(@SerialName("verification_required") val verificationRequired: Boolean = false)

    @Serializable
    private class UserRef(val id: String, val email: String)

    @Serializable
    private class Tokens(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("device_id") val deviceId: String,
        val user: UserRef? = null,
    )

    @Serializable
    private class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

    @Serializable
    private class DeviceList(val devices: List<DeviceInfo>)

    @Serializable
    private class ShareList(val shares: List<ShareInfo>)

    @Serializable
    private class Hashes(val hashes: List<String>)

    @Serializable
    private class Missing(val missing: List<String>)

    @Serializable
    private class ItemWrite(@SerialName("base_revision") val baseRevision: Long, val body: JsonElement)

    @Serializable
    private class Written(val revision: Long, val seq: Long = 0)

    companion object {
        private const val ATTEMPTS = 5
        private val JSON = "application/json".toMediaType()
        private val OCTETS = "application/octet-stream".toMediaType()
        private val PDF = "application/pdf".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        /** The server's base URL as typed, with https assumed and no trailing slash. */
        fun normalize(server: String): String {
            val trimmed = server.trim().trimEnd('/')
            return if ("://" in trimmed) trimmed else "https://$trimmed"
        }
    }
}
