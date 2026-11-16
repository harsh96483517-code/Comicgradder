package com.comicgrabber.app

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ComicItem(val title: String, val url: String, val direct: Boolean = false)

enum class Status { QUEUED, RESOLVING, DOWNLOADING, DONE, FAILED }

data class DlJob(
    val id: Int,
    val comic: ComicItem,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val message: String = "Waiting",
    val savedAs: String? = null
)

/** Shared between WebView and OkHttp so Cloudflare / session cookies + UA match. */
object Session {
    @Volatile var userAgent: String =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}

private object WebViewCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = CookieManager.getInstance()
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        CookieManager.getInstance().getCookie(url.toString())
            ?.split(";")?.mapNotNull { Cookie.parse(url, it.trim()) }
            ?: emptyList()
}

class DownloadEngine(private val ctx: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _jobs = MutableStateFlow<List<DlJob>>(emptyList())
    val jobs: StateFlow<List<DlJob>> = _jobs
    private var worker: Job? = null
    private var seq = 0

    private val client = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar)
        .followRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", Session.userAgent).build())
        }
        .build()

    // ---------------------------------------------------------------- queue

    @Synchronized
    fun enqueue(items: List<ComicItem>) {
        val busy = _jobs.value.filter { it.status != Status.DONE && it.status != Status.FAILED }
            .map { it.comic.url }.toSet()
        val fresh = items.filter { it.url !in busy }.map { DlJob(seq++, it) }
        if (fresh.isEmpty()) return
        _jobs.update { it + fresh }
        if (worker?.isActive != true) worker = scope.launch { runQueue() }
    }

    fun cancelAll() {
        worker?.cancel()
        _jobs.update { list ->
            list.map {
                if (it.status != Status.DONE && it.status != Status.FAILED)
                    it.copy(status = Status.FAILED, message = "Cancelled") else it
            }
        }
    }

    fun clearFinished() {
        _jobs.update { l -> l.filter { it.status != Status.DONE && it.status != Status.FAILED } }
    }

    private suspend fun runQueue() {
        while (true) {
            val job = _jobs.value.firstOrNull { it.status == Status.QUEUED } ?: break
            try {
                process(job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                patch(job.id) { copy(status = Status.FAILED, message = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun patch(id: Int, f: DlJob.() -> DlJob) {
        _jobs.update { l -> l.map { if (it.id == id) it.f() else it } }
    }

    // -------------------------------------------------------------- pipeline

    private suspend fun process(job: DlJob) {
        patch(job.id) { copy(status = Status.RESOLVING, message = "Opening page…") }

        // Direct file URL (e.g. user tapped the site's download link inside the WebView)
        if (job.comic.direct) {
            if (!tryDirect(job, job.comic.url, job.comic.url)) error("Server did not return a file")
            return
        }

        val html = get(job.comic.url, null).use { r ->
            if (!r.isSuccessful) error("Page returned HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val doc = Jsoup.parse(html, job.comic.url)

        // 1) The website's own Download button / ?dl= link
        val link = findDownloadLink(doc)
        if (link != null) {
            patch(job.id) { copy(message = "Following site download link…") }
            if (tryDirect(job, link, job.comic.url)) return
        }

        // 2) Fallback: scrape images → ZIP
        fallbackImages(job, doc)
    }

    /** Looks for the site's Download button: ?dl= links first, then "Download" text, then file links. */
    private fun findDownloadLink(doc: Document): String? {
        val anchors = doc.select("a[href]")
        val bad = { h: String -> h.isBlank() || h.startsWith("#") || h.startsWith("javascript", true) }
        anchors.firstOrNull { it.attr("href").contains("dl=") }?.absUrl("href")?.takeIf { it.isNotBlank() }
            ?.let { return it }
        anchors.firstOrNull { it.text().contains("download", true) && !bad(it.attr("href")) }
            ?.absUrl("href")?.takeIf { it.isNotBlank() }?.let { return it }
        val fileRx = Regex("""(?i)\.(zip|rar|7z|cbz|cbr|pdf)(\?.*)?$""")
        return anchors.firstOrNull { fileRx.containsMatchIn(it.attr("href")) }
            ?.absUrl("href")?.takeIf { it.isNotBlank() }
    }

    /** Follows up to 3 hops (the ?dl= URL may redirect or show an intermediate page). */
    private suspend fun tryDirect(job: DlJob, start: String, page: String): Boolean {
        var url = start
        var referer = page
        repeat(3) {
            get(url, referer).use { resp ->
                if (!resp.isSuccessful) return false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                val cd = resp.header("Content-Disposition")
                val isHtml = ct.startsWith("text/html") || ct.contains("xhtml")
                val isFile = cd?.contains("attachment", true) == true ||
                        (ct.isNotEmpty() && !isHtml && !ct.startsWith("text/"))
                if (isFile) {
                    saveFile(job, resp, cd, ct)
                    return true
                }
                val doc = Jsoup.parse(resp.body?.string().orEmpty(), resp.request.url.toString())
                val next = findDownloadLink(doc) ?: return false
                if (next == url) return false
                referer = url
                url = next
            }
        }
        return false
    }

    private suspend fun saveFile(job: DlJob, resp: Response, cd: String?, ct: String) {
        val serverName = filenameFrom(cd) ?: resp.request.url.pathSegments.lastOrNull().orEmpty()
        val known = setOf("zip", "rar", "7z", "pdf", "cbz", "cbr", "epub")
        val ext = serverName.substringAfterLast('.', "").lowercase().takeIf { it in known } ?: extFromMime(ct)
        val name = "${clean(job.comic.title)}.$ext"
        patch(job.id) { copy(status = Status.DOWNLOADING, message = "Downloading $name") }

        val body = resp.body ?: error("Empty response")
        val total = body.contentLength()
        val sink = Storage.open(ctx, name, mimeFor(ext))
        try {
            val buf = ByteArray(32 * 1024)
            var done = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.stream.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            patch(job.id) { copy(progress = pct / 100f) }
                        }
                    }
                }
            }
            sink.finish()
        } catch (e: Throwable) {
            sink.abort()
            throw e
        }
        patch(job.id) {
            copy(status = Status.DONE, progress = 1f, message = "Saved to Downloads/ComicGrabber", savedAs = name)
        }
    }

    // ------------------------------------------------------------- fallback

    private suspend fun fallbackImages(job: DlJob, first: Document) {
        patch(job.id) { copy(status = Status.DOWNLOADING, message = "No download link – scraping images…") }
        val base = job.comic.url.trimEnd('/')

        val pageUrls = linkedSetOf(job.comic.url)
        first.select(".page-links a[href], a.post-page-numbers[href], .pagination a[href]")
            .map { it.absUrl("href") }
            .filter { it.startsWith(base) }
            .forEach { pageUrls += it }

        val images = linkedSetOf<String>()
        for (p in pageUrls.take(150)) {
            currentCoroutineContext().ensureActive()
            val doc: Document = if (p == job.comic.url) first else {
                get(p, job.comic.url).use { r ->
                    if (r.isSuccessful) Jsoup.parse(r.body?.string().orEmpty(), p) else null
                } ?: continue
            }
            images += collectImages(doc)
        }
        if (images.isEmpty()) error("No download link and no images found")

        val list = images.toList()
        val name = "${clean(job.comic.title)}.zip"
        val sink = Storage.open(ctx, name, "application/zip")
        val zip = ZipOutputStream(sink.stream)
        zip.setLevel(0) // images are already compressed
        var saved = 0
        try {
            list.forEachIndexed { i, u ->
                currentCoroutineContext().ensureActive()
                try {
                    get(u, job.comic.url).use { r ->
                        if (r.isSuccessful) {
                            zip.putNextEntry(ZipEntry("%03d.%s".format(saved + 1, extOf(u))))
                            r.body?.byteStream()?.copyTo(zip)
                            zip.closeEntry()
                            saved++
                        }
                    }
                } catch (_: IOException) { /* skip broken image */ }
                patch(job.id) { copy(progress = (i + 1f) / list.size, message = "Image ${i + 1}/${list.size}") }
            }
            if (saved == 0) error("Could not download any image")
            zip.close()
            sink.finish()
        } catch (e: Throwable) {
            sink.abort()
            throw e
        }
        patch(job.id) {
            copy(status = Status.DONE, progress = 1f, message = "ZIP of $saved images saved", savedAs = name)
        }
    }

    private fun collectImages(doc: Document): List<String> {
        val imgRx = Regex("""(?i)\.(jpe?g|png|webp|gif)(\?.*)?$""")
        val skip = Regex("""(?i)logo|avatar|emoji|icon|banner|sprite|gravatar""")
        val scope = doc.selectFirst(".entry-content, .post-content, article, #content") ?: doc.body()
        return scope.select("img").mapNotNull { img ->
            val wrapped = img.parent()?.takeIf { it.tagName() == "a" }?.absUrl("href")
                ?.takeIf { imgRx.containsMatchIn(it) }
            wrapped ?: listOf("data-src", "data-lazy-src", "data-original", "src")
                .map { img.absUrl(it) }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        }.filter { !skip.containsMatchIn(it) }.distinct()
    }

    // -------------------------------------------------------------- helpers

    private fun get(url: String, referer: String?): Response =
        client.newCall(
            Request.Builder().url(url).apply { if (referer != null) header("Referer", referer) }.build()
        ).execute()

    private fun filenameFrom(cd: String?): String? {
        if (cd == null) return null
        Regex("""filename\*=(?:UTF-8'')?([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
            ?.let { return runCatching { URLDecoder.decode(it.trim('"', ' '), "UTF-8") }.getOrNull() }
        return Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
    }

    private fun clean(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .replace(Regex("\\s+"), " ").trim().take(120).ifBlank { "comic" }

    private fun extOf(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "jpg").lowercase().take(4)

    private fun extFromMime(ct: String) = when {
        ct.contains("pdf") -> "pdf"
        ct.contains("rar") -> "rar"
        ct.contains("7z") -> "7z"
        else -> "zip"
    }

    private fun mimeFor(ext: String) = when (ext) {
        "zip", "cbz" -> "application/zip"
        "pdf" -> "application/pdf"
        "rar", "cbr" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "epub" -> "application/epub+zip"
        else -> "application/octet-stream"
    }
}
