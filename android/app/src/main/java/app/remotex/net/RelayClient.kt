package app.remotex.net

import app.remotex.model.Host
import app.remotex.model.HostsResponse
import app.remotex.model.OpenSessionRequest
import app.remotex.model.OpenSessionResponse
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

    suspend fun openSession(userToken: String, hostId: String): String = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(OpenSessionRequest.serializer(), OpenSessionRequest(hostId))
        val req = Request.Builder()
            .url("$baseUrl/api/sessions")
            .header("Authorization", "Bearer $userToken")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "openSession: ${resp.code} ${resp.message}" }
            val body = resp.body?.string().orEmpty()
            json.decodeFromString(OpenSessionResponse.serializer(), body).sessionId
        }
    }
}
