package app.depot.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Runs the client against a stand-in for api.github.com and for the storage host it redirects to. */
class GitHubTest {
    private val api = MockWebServer()
    private val storage = MockWebServer()
    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private var storageStatus = 200

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    @Before fun start() {
        storage.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (storageStatus == 200) MockResponse().setBody(Buffer().write(apk))
                else MockResponse().setResponseCode(storageStatus)
        }
        storage.start()
        api.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer good") {
                    return json(401, """{"message":"Bad credentials"}""")
                }
                return when (request.requestUrl!!.encodedPath) {
                    "/user" -> json(200, """{"login":"me"}""")
                    "/repos/me/app/releases" -> json(
                        200,
                        """[{"tag_name":"v2","draft":false,"prerelease":false,
                            "assets":[{"id":42,"name":"app-release.apk","size":${apk.size}}]}]""",
                    )
                    "/repos/me/empty/releases" -> json(200, "[]")
                    "/repos/me/app/releases/assets/42" -> MockResponse().setResponseCode(302)
                        .setHeader("Location", storage.url("/blob?sig=x").toString())
                    else -> json(404, """{"message":"Not Found"}""")
                }
            }
        }
        api.start()
    }

    @After fun stop() {
        api.shutdown()
        storage.shutdown()
    }

    private fun client(token: String? = "good") = GitHub(api.url("/").toString().trimEnd('/')) { token }
    private fun tempFile() = File(Files.createTempDirectory("depot").toFile(), "out.apk")

    @Test fun readsLoginAndLatestRelease() {
        assertEquals("me", client().login())
        val release = client().latestRelease(RepoEntry("me", "app"))!!
        assertEquals("v2", release.tag)
        assertEquals(Asset(42, "app-release.apk", apk.size.toLong()), release.assets.single())
        assertNull(client().latestRelease(RepoEntry("me", "empty")))
    }

    @Test fun downloadFollowsRedirectWithoutLeakingToken() {
        val dest = tempFile()
        val progress = mutableListOf<Float>()
        client().download(RepoEntry("me", "app"), Asset(42, "app-release.apk", apk.size.toLong()), dest) { progress += it }
        assertArrayEquals(apk, dest.readBytes())
        assertEquals(1f, progress.last(), 0f)
        assertEquals(progress.sorted(), progress)

        val toApi = api.takeRequest()
        assertEquals("Bearer good", toApi.getHeader("Authorization"))
        assertEquals("application/octet-stream", toApi.getHeader("Accept"))
        assertEquals(1, storage.requestCount)
        assertNull(storage.takeRequest().getHeader("Authorization"))
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun failedDownloadLeavesNoFile() {
        storageStatus = 500
        val dest = tempFile()
        try {
            client().download(RepoEntry("me", "app"), Asset(42, "app-release.apk", 1), dest) {}
            fail("expected a failure")
        } catch (e: GitHubException) {
            assertEquals(500, e.code)
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun explainsBadTokenAndMissingRepo() {
        try {
            client("wrong").login()
            fail("expected a failure")
        } catch (e: GitHubException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("token"))
        }
        try {
            client().latestRelease(RepoEntry("me", "nope"))
            fail("expected a failure")
        } catch (e: GitHubException) {
            assertEquals(404, e.code)
            assertTrue(e.message!!.contains("no access"))
        }
    }
}
