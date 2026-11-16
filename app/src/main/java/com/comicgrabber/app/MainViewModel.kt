package com.comicgrabber.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject

data class PageInfo(val type: String = "other", val count: Int = 0, val url: String = "", val title: String = "")

class MainViewModel(app: Application) : AndroidViewModel(app) {
    companion object { const val DEFAULT_URL = "https://fsicomics.com/" }

    val engine = DownloadEngine(app)
    private val prefs = app.getSharedPreferences("cg", Context.MODE_PRIVATE)

    var startUrl by mutableStateOf(prefs.getString("start", DEFAULT_URL) ?: DEFAULT_URL)
        private set
    var page by mutableStateOf(PageInfo())
        private set

    /** url -> title; survives page navigation so you can select across several listing pages. */
    val selected = mutableStateMapOf<String, String>()

    fun setStart(raw: String): String {
        var u = raw.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        startUrl = u
        prefs.edit().putString("start", u).apply()
        return u
    }

    fun toggle(url: String, title: String, on: Boolean) {
        if (on) selected[url] = title else selected.remove(url)
    }

    /** Set when the corner download button of a card is tapped; UI consumes it (permission check + enqueue). */
    var pendingDownload by mutableStateOf<ComicItem?>(null)
        private set

    fun requestDownload(url: String, title: String) {
        pendingDownload = ComicItem(title.ifBlank { "comic" }, url)
    }

    fun consumePending() { pendingDownload = null }

    fun onNavigate(url: String) { page = PageInfo(url = url) }

    fun onPage(json: String) {
        runCatching {
            val o = JSONObject(json)
            page = PageInfo(o.getString("type"), o.optInt("count"), o.optString("url"), o.optString("title"))
        }
    }

    fun selectedJson(): String = JSONArray(selected.keys.toList()).toString()

    fun downloadSelected() {
        engine.enqueue(selected.map { ComicItem(it.value.ifBlank { "comic" }, it.key) })
        selected.clear()
    }

    fun downloadCurrent() {
        val title = page.title.split(Regex("\\s[|–—-]\\s")).first().trim().ifBlank { "comic" }
        engine.enqueue(listOf(ComicItem(title, page.url)))
    }
}
