package app.depot.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GitHubException(val code: Int, message: String) : IOException(message)

/** Minimal GitHub REST client: just what a store needs, with no third-party dependencies. */
class GitHub(private val api: String = "https://api.github.com", private val token: () -> String?) {
    private val apiUrl = URL(api)

    fun login(): String = JSONObject(get("/user")).getString("login")

    /** Repositories the token can see, most recently pushed first. */
    fun repositories(): List<String> {
        val arr = JSONArray(get("/user/repos?per_page=100&sort=pushed"))
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("full_name") }
    }

    /** Newest published release, or null when the repository has none. */
    fun latestRelease(repo: RepoEntry): Release? =
        parseReleases(get("/repos/${repo.owner}/${repo.name}/releases?per_page=15"), repo.prerelease)

    fun download(repo: RepoEntry, asset: Asset, dest: File, onProgress: (Float) -> Unit) {
        var url = URL("$api/repos/${repo.owner}/${repo.name}/releases/assets/${asset.id}")
        val tmp = File(dest.parentFile, dest.name + ".part")
        dest.parentFile?.mkdirs()
        // GitHub answers with a redirect to short-lived storage. Follow it by hand so the
        // token is only ever sent to api.github.com.
        repeat(6) {
            val conn = open(url, accept = "application/octet-stream")
            conn.instanceFollowRedirects = false
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    url = URL(url, location)
                    if (apiUrl.protocol == "https" && url.protocol != "https") throw IOException("Refusing a non-HTTPS download")
                    return@repeat
                }
                if (code != 200) throw failure(conn, code)
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (!tmp.renameTo(dest)) throw IOException("Could not save the download")
                return
            } catch (e: IOException) {
                tmp.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun get(path: String): String {
        val conn = open(URL(api + path), accept = "application/vnd.github+json")
        try {
            val code = conn.responseCode
            if (code != 200) throw failure(conn, code)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: URL, accept: String): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "depot-store")
        conn.setRequestProperty("Accept", accept)
        // The token goes to the API itself and nowhere else, whatever a redirect points at.
        if (url.host == apiUrl.host && url.protocol == apiUrl.protocol && port(url) == port(apiUrl)) {
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            token()?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        }
        return conn
    }

    private fun port(url: URL) = if (url.port == -1) url.defaultPort else url.port

    private fun failure(conn: HttpURLConnection, code: Int): GitHubException {
        val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val detail = runCatching { JSONObject(body ?: "").optString("message") }.getOrNull().orEmpty()
        val remaining = conn.getHeaderField("X-RateLimit-Remaining")
        val message = when {
            code == 401 -> "GitHub rejected the token. Check it in Settings."
            code == 403 && remaining == "0" -> "GitHub rate limit reached. Try again later."
            code == 403 -> "The token is not allowed to do this" + if (detail.isEmpty()) "." else ": $detail"
            code == 404 -> "Not found. Either the repository does not exist or the token has no access to it."
            else -> "GitHub error $code" + if (detail.isEmpty()) "" else ": $detail"
        }
        return GitHubException(code, message)
    }

    companion object {
        fun parseReleases(json: String, includePrerelease: Boolean): Release? {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                if (r.optBoolean("prerelease") && !includePrerelease) continue
                return parseRelease(r)
            }
            return null
        }

        private fun parseRelease(r: JSONObject): Release {
            val assets = r.optJSONArray("assets") ?: JSONArray()
            return Release(
                tag = r.optString("tag_name"),
                title = r.optString("name").takeUnless { it.isBlank() || it == "null" } ?: r.optString("tag_name"),
                notes = r.optString("body").takeUnless { it == "null" }.orEmpty(),
                publishedAt = r.optString("published_at"),
                assets = (0 until assets.length()).map {
                    val a = assets.getJSONObject(it)
                    Asset(a.getLong("id"), a.getString("name"), a.optLong("size"))
                },
            )
        }
    }
}
