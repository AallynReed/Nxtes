package net.mildhope.nxtes.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var account: SyncAccount
    private lateinit var client: SyncClient

    @Before
    fun setUp() {
        server.start()
        account = SyncAccount(tmp.newFile("account.json"))
        account.signIn(Session(server.url("/").toString(), "ana@example.org", "u1", "d1", "old-access", "old-refresh"))
        client = SyncClient(account)
    }

    @After
    fun tearDown() = server.close()

    private fun reply(code: Int, body: String = "", vararg headers: Pair<String, String>) =
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { addHeader(it.first, it.second) } }.build())

    @Test
    fun refusedTokenIsRenewedOnceAndTheRequestRetried() = runBlocking {
        reply(401, """{"error":"unauthorized"}""")
        reply(200, """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"device_id":"d1"}""")
        reply(200, """{"used":10,"quota":100}""")

        assertEquals(Usage(10, 100), client.usage())

        assertEquals("Bearer old-access", server.takeRequest().headers["Authorization"])
        assertEquals("/v1/auth/refresh", server.takeRequest().url.encodedPath)
        assertEquals("Bearer new-access", server.takeRequest().headers["Authorization"])
        assertEquals("new-refresh", account.session?.refreshToken)
    }

    @Test
    fun refusedRefreshSignsOut() = runBlocking {
        reply(401, """{"error":"unauthorized"}""")
        reply(401, """{"error":"unauthorized"}""")

        try {
            client.usage()
            fail("Expected the request to fail")
        } catch (e: ApiException) {
            assertEquals(401, e.status)
        }
        assertNull(account.session)
        assertTrue(account.state.value.expired)
        assertEquals("ana@example.org", account.state.value.email)
    }

    @Test
    fun slowDownIsWaitedOut() = runBlocking {
        reply(429, """{"error":"slow_down"}""", "Retry-After" to "0")
        reply(200, """{"missing":["abc"]}""")

        assertEquals(listOf("abc"), client.missingBlobs(listOf("abc", "abc")))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun conflictCarriesTheServerCopy() = runBlocking {
        reply(409, """{"error":"conflict","item":{"kind":"document","id":"x","revision":4,"seq":9,"deleted":false,"body":{"format":1}}}""")

        try {
            client.putItem(KIND_DOCUMENT, "x", 2, JsonPrimitive(1))
            fail("Expected a conflict")
        } catch (e: ApiException) {
            assertEquals(4L, e.item?.revision)
            assertEquals("x", e.item?.id)
        }
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/documents/x", request.url.encodedPath)
        assertTrue(request.body!!.utf8().contains("\"base_revision\":2"))
    }
}
