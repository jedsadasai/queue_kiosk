package com.queuekiosk.data

import com.queuekiosk.data.api.ApiClientFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Date

/** Login + add-queue against a fake fleQ server: request shape, response parsing, token handling. */
class ApiFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var session: FakeSession
    private lateinit var auth: AuthRepository
    private lateinit var queue: QueueRepository

    private val loginOk = """
        {"status":"success","data":{"token_type":"Bearer","token":"tok-1","expire":3600,
         "branch":"สาขาหลัก","workflow":{"id":7,"name":"ผู้ป่วยนอก","letter":"A","branch_name":"โรงพยาบาลตัวอย่าง","start":1}}}
    """.trimIndent()

    private fun addQueueOk(number: String = "001") = """
        {"status":"success","data":{"number":"$number","total":"4","building":"อาคาร C ชั้น 1","doctor":null,
         "url_qr":"https://q.example/$number","vn":null,"worklfow_name":"ผู้ป่วยนอก"}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        session = FakeSession()
        val api = ApiClientFactory(session, debugLogging = false)
        auth = AuthRepository(session, api)
        queue = QueueRepository(session, api, auth, clock = { Date(0) })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().removeSuffix("/") // user typed it without a slash

    @Test
    fun `login posts form fields, sends no Authorization, and stores token and workflow`() = runTest {
        server.enqueue(MockResponse().setBody(loginOk))

        auth.login(baseUrl(), "kiosk1", "p@ss word")

        val req = server.takeRequest()
        assertEquals("/api/v1/login", req.path)
        assertEquals("POST", req.method)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("username=kiosk1&password=p%40ss%20word", req.body.readUtf8())
        assertNull(req.getHeader("Authorization"))

        assertEquals("Bearer tok-1", session.token)
        assertEquals(7, session.workflowId)
        assertEquals("ผู้ป่วยนอก", session.workflowName)
        assertEquals("โรงพยาบาลตัวอย่าง", session.branchName)
        assertEquals("kiosk1", session.username)
    }

    @Test
    fun `login 401 is BAD_CREDENTIALS and saves nothing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":"error"}"""))
        try {
            auth.login(baseUrl(), "kiosk1", "wrong")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiException.Kind.BAD_CREDENTIALS, e.kind)
        }
        assertEquals("", session.token)
        assertEquals("", session.baseUrl)
    }

    @Test
    fun `login 200 with an HTML page is BAD_RESPONSE`() = runTest {
        server.enqueue(MockResponse().setBody("<html>not the api</html>"))
        try {
            auth.login(baseUrl(), "kiosk1", "pw")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiException.Kind.BAD_RESPONSE, e.kind)
        }
    }

    @Test
    fun `add-queue sends workflow_id with the current token and parses the ticket`() = runTest {
        server.enqueue(MockResponse().setBody(loginOk))
        server.enqueue(MockResponse().setBody(addQueueOk("001")))
        auth.login(baseUrl(), "kiosk1", "pw")
        server.takeRequest()

        val ticket = queue.addQueue()

        val req = server.takeRequest()
        assertEquals("/api/v1/add-queue", req.path)
        assertEquals("workflow_id=7", req.body.readUtf8())
        assertEquals("Bearer tok-1", req.getHeader("Authorization"))

        assertEquals("0001", ticket.number) // fleQ pads 3-char numbers to 4
        assertEquals("4", ticket.total)
        assertEquals("อาคาร C ชั้น 1", ticket.building)
        assertEquals("https://q.example/001", ticket.urlQr)
        assertEquals("ผู้ป่วยนอก", ticket.workflowName) // from the misspelt "worklfow_name"
    }

    @Test
    fun `token is read per request, not captured when the client is built`() = runTest {
        session.baseUrl = baseUrl()
        session.workflowId = 7
        session.token = "Bearer old"
        server.enqueue(MockResponse().setBody(addQueueOk("001")))
        server.enqueue(MockResponse().setBody(addQueueOk("002")))

        queue.addQueue()
        session.token = "Bearer new"
        queue.addQueue()

        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `expired token - 401 triggers one re-login and a retry`() = runTest {
        session.baseUrl = baseUrl()
        session.username = "kiosk1"
        session.password = "pw"
        session.workflowId = 7
        session.token = "Bearer expired"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(loginOk))
        server.enqueue(MockResponse().setBody(addQueueOk("1234")))

        val ticket = queue.addQueue()

        assertEquals("/api/v1/add-queue", server.takeRequest().path)
        assertEquals("/api/v1/login", server.takeRequest().path)
        val retry = server.takeRequest()
        assertEquals("/api/v1/add-queue", retry.path)
        assertEquals("Bearer tok-1", retry.getHeader("Authorization"))
        assertEquals("1234", ticket.number) // 4 chars: unchanged
    }

    @Test
    fun `re-login retries quickly at first - 1 s then 2 s - and runs beforeAttempt every time`() = runTest {
        session.baseUrl = baseUrl()
        session.username = "kiosk1"
        session.password = "pw"
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(loginOk))
        var attempts = 0
        val errors = mutableListOf<ApiException.Kind>()

        val data = auth.reLoginWithRetry(beforeAttempt = { attempts++ }, onRetry = { errors += it.kind })

        assertEquals(7, data.workflow?.id)
        assertEquals(3, attempts)
        assertEquals(listOf(ApiException.Kind.SERVER, ApiException.Kind.SERVER), errors)
        assertEquals(1_000L + 2_000L, testScheduler.currentTime) // virtual time spent waiting
        assertEquals("Bearer tok-1", session.token)
    }

    @Test
    fun `re-login retry waits repeat the last delay`() {
        val delays = AuthRepository.RETRY_DELAYS_MS
        assertEquals(5_000L, delays.last()) // fleQ's interval stays the steady state
        assertTrue(delays.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `add-queue without a login is NOT_LOGGED_IN and makes no request`() = runTest {
        try {
            queue.addQueue()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiException.Kind.NOT_LOGGED_IN, e.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `padNumber only pads 3 characters`() {
        assertEquals("0012", QueueRepository.padNumber("012"))
        assertEquals("12", QueueRepository.padNumber("12"))
        assertEquals("A0012", QueueRepository.padNumber("A0012"))
    }
}
