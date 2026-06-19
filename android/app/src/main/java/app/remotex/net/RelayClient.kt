package app.remotex.net

import app.remotex.model.FsEntry
import app.remotex.model.FsListResponse
import app.remotex.model.Host
import app.remotex.model.HostTelemetryResponse
import app.remotex.model.HostsResponse
import app.remotex.model.ModelInfo
import app.remotex.model.ModelsResponse
import app.remotex.model.OpenSessionResponse
import app.remotex.model.ThreadInfo
import app.remotex.model.ThreadsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
        val body = buildJsonObject {
            put("host_id", hostId)
            fun putIfSet(key: String, value: String?) {
                if (!value.isNullOrBlank()) put(key, value)
            }
            putIfSet("thread_id", resumeThreadId)
            putIfSet("cwd", cwd)
        }
        val req = Request.Builder()
            .url("$baseUrl/api/sessions")
            .header("Authorization", "Bearer $userToken")
            .post(body.toString().toRequestBody(jsonMedia))
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

    suspend fun readFile(
        userToken: String,
        hostId: String,
        path: String,
    ): WorkspaceFile = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        val req = Request.Builder()
            .url("$baseUrl/api/hosts/$hostId/fs/read?path=$encoded")
            .header("Authorization", "Bearer $userToken")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "readFile: ${resp.code} ${resp.message}" }
            val body = resp.body?.string().orEmpty()
            val obj = json.parseToJsonElement(body) as JsonObject
            WorkspaceFile(
                path = obj["path"]?.jsonPrimitive?.contentOrNull ?: path,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/'),
                mime = obj["mime"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream",
                size = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                base64 = obj["base64"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    suspend fun deleteFile(
        userToken: String,
        hostId: String,
        path: String,
    ): Unit = withContext(Dispatchers.IO) {
        val body = """{"path":"${path.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
        val req = Request.Builder()
            .url("$baseUrl/api/hosts/$hostId/fs/delete")
            .header("Authorization", "Bearer $userToken")
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("deleteFile: ${resp.code} ${resp.message} ${resp.body?.string().orEmpty()}")
            }
        }
    }

    suspend fun renameFile(
        userToken: String,
        hostId: String,
        from: String,
        to: String,
    ): Unit = withContext(Dispatchers.IO) {
        val esc = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"") }
        val body = """{"from":"${esc(from)}","to":"${esc(to)}"}"""
        val req = Request.Builder()
            .url("$baseUrl/api/hosts/$hostId/fs/rename")
            .header("Authorization", "Bearer $userToken")
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("renameFile: ${resp.code} ${resp.message} ${resp.body?.string().orEmpty()}")
            }
        }
    }

    suspend fun uploadFile(
        userToken: String,
        hostId: String,
        targetDir: String,
        fileName: String,
        bytes: ByteArray,
        mime: String = "application/octet-stream",
    ): Unit = withContext(Dispatchers.IO) {
        val multipart = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("path", targetDir)
            .addFormDataPart(
                "file",
                fileName,
                bytes.toRequestBody(mime.toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url("$baseUrl/api/hosts/$hostId/fs/upload")
            .header("Authorization", "Bearer $userToken")
            .post(multipart)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("uploadFile: ${resp.code} ${resp.message} ${resp.body?.string().orEmpty()}")
            }
        }
    }

    data class WorkspaceFile(
        val path: String,
        val name: String,
        val mime: String,
        val size: Long,
        val base64: String,
    )

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

}

typealias FsEntryInfo = FsEntry
