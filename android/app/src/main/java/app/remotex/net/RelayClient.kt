package app.remotex.net

import app.remotex.model.FsEntry
import app.remotex.model.FsListResponse
import app.remotex.model.Host
import app.remotex.model.HostTelemetryResponse
import app.remotex.model.HostsResponse
import app.remotex.model.ModelInfo
import app.remotex.model.ModelsResponse
import app.remotex.model.OpenSessionRequest
import app.remotex.model.OpenSessionResponse
import app.remotex.model.SearchResponse
import app.remotex.model.SearchResult
import app.remotex.model.ThreadInfo
import app.remotex.model.ThreadsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class RelayClient(
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    /** GET /api/models — relay-provided model picker list. Unauthenticated. */
    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/models")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "listModels: ${resp.code} ${resp.message}" }
            val body = resp.body?.string().orEmpty()
            json.decodeFromString(ModelsResponse.serializer(), body).models
        }
    }

    suspend fun listHosts(userToken: String): List<Host> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/hosts")
            .header("Authorization", "Bearer $userToken")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "listHosts: ${resp.code} ${resp.message}" }
            val body = resp.body?.string().orEmpty()
            json.decodeFromString(HostsResponse.serializer(), body).hosts
        }
    }

    suspend fun openSession(
        userToken: String,
        hostId: String,
        resumeThreadId: String? = null,
        cwd: String? = null,
    ): String = withContext(Dispatchers.IO) {
        // Hand-build the JSON so optional fields are only present when
        // set — relay treats empty strings as null and expects missing
        // keys for non-overrides.
        val body = buildString {
            append('{')
            append("\"host_id\":\"").append(hostId).append('"')
            if (!resumeThreadId.isNullOrBlank()) {
                append(",\"thread_id\":\"").append(resumeThreadId).append('"')
            }
            if (!cwd.isNullOrBlank()) {
                append(",\"cwd\":\"")
                append(cwd.replace("\\", "\\\\").replace("\"", "\\\""))
                append('"')
            }
            append('}')
        }
        val req = Request.Builder()
            .url("$baseUrl/api/sessions")
            .header("Authorization", "Bearer $userToken")
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "openSession: ${resp.code} ${resp.message}" }
            val respBody = resp.body?.string().orEmpty()
            json.decodeFromString(OpenSessionResponse.serializer(), respBody).sessionId
        }
    }

    suspend fun listThreads(
        userToken: String,
        hostId: String,
        limit: Int = 20,
    ): List<ThreadInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/hosts/$hostId/threads?limit=$limit")
            .header("Authorization", "Bearer $userToken")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "listThreads: ${resp.code} ${resp.message}" }
            val body = resp.body?.string().orEmpty()
            json.decodeFromString(ThreadsResponse.serializer(), body).threads
        }
    }

    suspend fun readDirectory(
        userToken: String,
        hostId: String,
        path: String,
    ): FsListResponse = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        val req = Request.Builder()
            .url("$baseUrl/api/hosts/$hostId/fs?path=$encoded")
            .header("Authorization", "Bearer $userToken")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "readDirectory: ${resp.code} ${resp.message}" }
            val body = resp.body?.string().orEmpty()
            json.decodeFromString(FsListResponse.serializer(), body)
        }
    }

    suspend fun mkdir(
        userToken: String,
        hostId: String,
        parent: String,
        name: String,
    ): Unit = withContext(Dispatchers.IO) {
        val body = buildString {
            append('{')
            append("\"path\":\"").append(parent.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            append(",\"name\":\"").append(name.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            append('}')
        }
        val req = Request.Builder()
            .url("$baseUrl/api/hosts/$hostId/fs/mkdir")
            .header("Authorization", "Bearer $userToken")
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = resp.body?.string().orEmpty()
                error("mkdir: ${resp.code} ${resp.message} $msg")
            }
        }
    }

    suspend fun getHostTelemetry(
        userToken: String,
        hostId: String,
    ): HostTelemetryResponse = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/hosts/$hostId/telemetry")
            .header("Authorization", "Bearer $userToken")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "getHostTelemetry: ${resp.code} ${resp.message}" }
            val body = resp.body?.string().orEmpty()
            json.decodeFromString(HostTelemetryResponse.serializer(), body)
        }
    }

    suspend fun searchChats(
        userToken: String,
        query: String,
        limit: Int = 20,
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("$baseUrl/api/search?q=$encoded&limit=$limit")
            .header("Authorization", "Bearer $userToken")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "searchChats: ${resp.code} ${resp.message}" }
            val body = resp.body?.string().orEmpty()
            json.decodeFromString(SearchResponse.serializer(), body).results
        }
    }
}

typealias FsEntryInfo = FsEntry
