package com.surveyme.data

import com.surveyme.data.model.NoteAction
import com.surveyme.data.model.NoteActionKind
import com.surveyme.data.model.NoteStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Item 17: the notes API client end to end against a local HTTP server. [OsmNotesServiceTest]
 * covers the parser on its own; this class covers what goes over the wire (URL, method,
 * headers, body) and how non-2xx answers surface, which is what the queue and the fetcher
 * key their behaviour on.
 */
@RunWith(RobolectricTestRunner::class)
class OsmNotesServiceHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var service: OsmNotesService

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        // The service concatenates "$baseUrl/api/..."; server.url("/") ends in a slash.
        service = OsmNotesService(server.url("/").toString().trimEnd('/'))
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(2, TimeUnit.SECONDS) ?: throw AssertionError("no request reached the server")

    // Shape of osm.org /api/0.6/notes.json: one open note with an anonymous reply, one closed.
    private val notesCollection = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-79.4153327,43.7787206]},
           "properties":{"id":5534047,"date_created":"2026-09-23 01:58:03 UTC","status":"open",
             "comments":[
               {"date":"2026-09-23 01:58:03 UTC","uid":12345,"user":"mapper","action":"opened","text":"Routing error.\n\nSecond paragraph."},
               {"date":"2026-09-24 10:00:00 UTC","action":"commented","text":"Anonymous reply"}
             ]}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-79.40,43.70]},
           "properties":{"id":99,"date_created":"2026-09-01 08:00:00 UTC","status":"closed","closed_at":"2026-09-20 12:30:00 UTC",
             "comments":[
               {"date":"2026-09-01 08:00:00 UTC","uid":1,"user":"a","action":"opened","text":"Is this shop open?"},
               {"date":"2026-09-20 12:30:00 UTC","uid":2,"user":"b","action":"closed","text":"Fixed"}
             ]}}
        ]}
    """.trimIndent()

    // What a comment / close / reopen / create answers with: the whole note after the change.
    private fun singleNote(id: Long, status: String, lastAction: String, lastText: String) = """
        {"type":"Feature","geometry":{"type":"Point","coordinates":[-79.40,43.70]},
         "properties":{"id":$id,"date_created":"2026-09-01 08:00:00 UTC","status":"$status",
           "comments":[
             {"date":"2026-09-01 08:00:00 UTC","uid":1,"user":"a","action":"opened","text":"Is this shop open?"},
             {"date":"2026-09-25 18:05:00 UTC","uid":7,"user":"me","action":"$lastAction","text":"$lastText"}
           ]}}
    """.trimIndent()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `bbox fetch asks notes json with closed 7, the page limit and a square around the point`() = runBlocking {
        server.enqueue(json(notesCollection))

        val page = service.fetchNotes(43.7, -79.4, radiusMeters = 2000)

        val request = takeRequest()
        assertEquals("GET", request.method)
        val url = request.requestUrl!!
        assertEquals("/api/0.6/notes.json", url.encodedPath)
        assertEquals("7", url.queryParameter("closed"))
        assertEquals(OsmNotesService.PAGE_LIMIT.toString(), url.queryParameter("limit"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("OSMFieldNotes/"))
        assertNull(request.getHeader("Authorization"))

        // bbox is min_lon,min_lat,max_lon,max_lat and brackets the centre by about 2 km.
        val bbox = url.queryParameter("bbox")!!.split(",").map { it.toDouble() }
        assertEquals(4, bbox.size)
        assertTrue(bbox[0] < -79.4 && -79.4 < bbox[2])
        assertTrue(bbox[1] < 43.7 && 43.7 < bbox[3])
        assertEquals(2000.0 / 111320.0, bbox[3] - 43.7, 1e-6)
        assertEquals(bbox[3] - 43.7, 43.7 - bbox[1], 1e-9)
        assertTrue(bbox[2] - (-79.4) > bbox[3] - 43.7) // a degree of longitude is shorter this far north

        assertFalse(page.truncated)
        assertEquals(listOf(5534047L, 99L), page.notes.map { it.note.noteId })
        assertEquals(NoteStatus.OPEN, page.notes[0].note.status)
        assertEquals("osm_note_5534047", page.notes[0].poi.id)
        assertEquals(43.7787206, page.notes[0].poi.latitude, 1e-9)
        assertEquals(2, page.notes[0].note.comments.size)
        assertEquals(NoteStatus.CLOSED, page.notes[1].note.status)
    }

    @Test
    fun `bbox fetch with a 429 surfaces the code and Retry-After`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "600").setBody("slow down"))

        try {
            service.fetchNotes(43.7, -79.4)
            fail("expected FetchHttpException")
        } catch (e: FetchHttpException) {
            assertEquals(429, e.code)
            assertEquals(600L, e.retryAfterSeconds)
            assertTrue(e.message!!.contains("retry after 600s"))
        }
    }

    @Test
    fun `fetch of one note by id hits notes slash id json`() = runBlocking {
        server.enqueue(json(singleNote(99, "open", "commented", "still there")))

        val fetched = service.fetchNote(99)

        assertEquals("/api/0.6/notes/99.json", takeRequest().path)
        assertNotNull(fetched)
        assertEquals(99L, fetched!!.note.noteId)
        assertEquals("still there", fetched.note.comments.last().text)
    }

    @Test
    fun `comment posts a form text field with the Bearer token to comment json`() = runBlocking {
        server.enqueue(json(singleNote(99, "open", "commented", "Checked it")))

        val fetched = service.postNoteAction(99, NoteActionKind.COMMENT, "  Checked it  ", "Bearer tok-123")

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/0.6/notes/99/comment.json", request.path)
        assertEquals("Bearer tok-123", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("text=Checked it", URLDecoder.decode(request.body.readUtf8(), "UTF-8"))

        assertEquals(99L, fetched.note.noteId)
        assertEquals(NoteAction.COMMENTED, fetched.note.comments.last().action)
        assertEquals("Checked it", fetched.note.comments.last().text)
    }

    @Test
    fun `close without a comment posts an empty form to close json and answers the closed note`() = runBlocking {
        server.enqueue(json(singleNote(99, "closed", "closed", "")))

        val fetched = service.postNoteAction(99, NoteActionKind.CLOSE, "   ", "Bearer tok-123")

        val request = takeRequest()
        assertEquals("/api/0.6/notes/99/close.json", request.path)
        assertEquals(0L, request.bodySize)
        assertEquals(NoteStatus.CLOSED, fetched.note.status)
    }

    @Test
    fun `reopen goes to reopen json`() = runBlocking {
        server.enqueue(json(singleNote(99, "open", "reopened", "")))
        service.postNoteAction(99, NoteActionKind.REOPEN, "", "Bearer tok-123")
        assertEquals("/api/0.6/notes/99/reopen.json", takeRequest().path)
    }

    @Test
    fun `create posts lat lon and text as query parameters with an empty body`() = runBlocking {
        server.enqueue(json(singleNote(5600001, "open", "opened", "Bench gone")))

        val fetched = service.createNote(43.65123, -79.38345, " Bench gone & path\nblocked #survey ", "Bearer tok-123")

        val request = takeRequest()
        assertEquals("POST", request.method)
        val url = request.requestUrl!!
        assertEquals("/api/0.6/notes.json", url.encodedPath)
        assertEquals("43.6512300", url.queryParameter("lat"))
        assertEquals("-79.3834500", url.queryParameter("lon"))
        assertEquals("Bench gone & path\nblocked #survey", url.queryParameter("text"))
        assertEquals("Bearer tok-123", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
        assertEquals(5600001L, fetched.note.noteId)
    }

    @Test
    fun `an anonymous create sends no Authorization header`() = runBlocking {
        server.enqueue(json(singleNote(5600002, "open", "opened", "Bench gone")))
        service.createNote(43.65123, -79.38345, "Bench gone", authHeader = null)
        assertNull(takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a 401 on a post surfaces as its own code with the server's text`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Couldn't authenticate you"))

        try {
            service.postNoteAction(99, NoteActionKind.COMMENT, "hi", "Bearer dead")
            fail("expected FetchHttpException")
        } catch (e: FetchHttpException) {
            assertEquals(401, e.code)
            assertNull(e.retryAfterSeconds)
            assertEquals("Couldn't authenticate you", e.body)
        }
    }

    @Test
    fun `a 409 keeps the reason osm org gives, an html body is dropped`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("The note 99 was closed at 2026-09-20 12:30:00 UTC"))
        try {
            service.postNoteAction(99, NoteActionKind.CLOSE, "", "Bearer tok")
            fail("expected FetchHttpException")
        } catch (e: FetchHttpException) {
            assertEquals(409, e.code)
            assertEquals("The note 99 was closed at 2026-09-20 12:30:00 UTC", e.body)
        }

        server.enqueue(MockResponse().setResponseCode(502).setBody("<html><body>Bad Gateway</body></html>"))
        try {
            service.createNote(43.0, -79.0, "x", null)
            fail("expected FetchHttpException")
        } catch (e: FetchHttpException) {
            assertEquals(502, e.code)
            assertNull(e.body)
        }
    }

    @Test
    fun `a 429 on a post carries Retry-After for the queue's backoff`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        try {
            service.createNote(43.0, -79.0, "x", "Bearer tok")
            fail("expected FetchHttpException")
        } catch (e: FetchHttpException) {
            assertEquals(429, e.code)
            assertEquals(30L, e.retryAfterSeconds)
        }
    }

    @Test
    fun `an answer that is not a note is an IOException, not a silent null`() = runBlocking {
        server.enqueue(json("""{"type":"FeatureCollection","features":[]}"""))
        try {
            service.postNoteAction(99, NoteActionKind.COMMENT, "hi", "Bearer tok")
            fail("expected IOException")
        } catch (e: FetchHttpException) {
            fail("a 200 must not be an HTTP error")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("not a note"))
        }
    }
}
