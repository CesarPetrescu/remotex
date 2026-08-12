package app.remotex.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.remotex.security.TokenStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenPersistenceInstrumentedTest {
    @Test
    fun acceptedTokenIsNormalizedBeforeLiveConnectionsUseIt() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/hosts" -> MockResponse().setResponseCode(200).setBody("""{"hosts":[]}""")
                "/api/models" -> MockResponse().setResponseCode(200).setBody("""{"models":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val relayUrl = server.url("/").toString()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val store = RecordingTokenStore()
        val viewModelStore = ViewModelStore()
        lateinit var viewModel: RemotexViewModel

        try {
            instrumentation.runOnMainSync {
                viewModel = RemotexViewModel(
                    application = application,
                    relayUrl = relayUrl,
                    tokenStore = store,
                )
                viewModelStore.put("trimmed-token", viewModel)
                viewModel.setToken("  accepted-token  ")
                viewModel.refresh()
            }

            waitUntil {
                viewModel.state.value.userToken == "accepted-token" &&
                    !viewModel.state.value.loading
            }
            assertEquals("accepted-token", store.stored)

            var hostsRequest: RecordedRequest? = null
            repeat(4) {
                val request = server.takeRequest(2, TimeUnit.SECONDS) ?: return@repeat
                if (request.path == "/api/hosts") hostsRequest = request
            }
            assertEquals("Bearer accepted-token", hostsRequest?.getHeader("Authorization"))
        } finally {
            instrumentation.runOnMainSync { viewModelStore.clear() }
            server.shutdown()
        }
    }

    @Test
    fun draftsAndRejectedTokensNeverPersist() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/hosts" -> if (
                    request.getHeader("Authorization") == "Bearer rejected-token"
                ) {
                    MockResponse().setResponseCode(401)
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"hosts":[{"id":"host-1","nickname":"Workstation","online":true}]}""")
                }
                "/api/models" -> MockResponse().setResponseCode(200).setBody("""{"models":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val relayUrl = server.url("/").toString()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val store = BlockingTokenStore()
        val viewModelStore = ViewModelStore()
        lateinit var viewModel: RemotexViewModel

        try {
            instrumentation.runOnMainSync {
                viewModel = RemotexViewModel(
                    application = application,
                    relayUrl = relayUrl,
                    tokenStore = store,
                )
                viewModelStore.put("token-race", viewModel)
                viewModel.setToken("accepted-token")
                viewModel.refresh()
            }

            assertTrue("accepted token was never saved", store.saveStarted.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { viewModel.setToken("new-draft") }
            store.allowSave.countDown()

            assertTrue("draft clear did not finish", store.secondClear.await(5, TimeUnit.SECONDS))
            waitUntil {
                viewModel.state.value.userToken == "new-draft" &&
                    viewModel.state.value.hosts.isEmpty() &&
                    !viewModel.state.value.loading
            }
            assertEquals("", store.stored)

            instrumentation.runOnMainSync {
                viewModel.setToken("rejected-token")
                viewModel.refresh()
            }
            waitUntil {
                viewModel.state.value.userToken.isEmpty() &&
                    viewModel.state.value.error == "That access token was not accepted."
            }
            assertEquals("", store.stored)
        } finally {
            store.allowSave.countDown()
            instrumentation.runOnMainSync { viewModelStore.clear() }
            server.shutdown()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("condition timed out", condition())
    }

    private class BlockingTokenStore : TokenStore {
        @Volatile
        var stored: String = "previous-token"
        val saveStarted = CountDownLatch(1)
        val allowSave = CountDownLatch(1)
        val secondClear = CountDownLatch(1)
        private var clearCount = 0

        override fun load(): String = ""

        override fun save(token: String) {
            saveStarted.countDown()
            check(allowSave.await(5, TimeUnit.SECONDS)) { "save was never released" }
            stored = token
        }

        @Synchronized
        override fun clear() {
            stored = ""
            clearCount += 1
            if (clearCount >= 2) secondClear.countDown()
        }
    }

    private class RecordingTokenStore : TokenStore {
        @Volatile
        var stored: String = ""

        override fun load(): String = ""
        override fun save(token: String) { stored = token }
        override fun clear() { stored = "" }
    }
}
