package com.github.tvbox.osc.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.catvod.crawler.JsLoader
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.util.SearchSettings
import com.github.tvbox.osc.util.UA
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SearchViewModel : ViewModel() {

    enum class ResultState { Pending, Done }

    data class SourceResult(
        val sourceKey: String,
        val sourceName: String,
        val state: ResultState,
        val videos: List<Movie.Video>,
        val arrivedAt: Int = Int.MAX_VALUE,
    )

    val results = MutableStateFlow<List<SourceResult>>(emptyList())
    val running = MutableStateFlow(false)
    val searchedTitle = MutableStateFlow("")
    val exactMatch = MutableStateFlow(false)
    val sitesEmpty = MutableStateFlow(false)

    val hotSearch = MutableStateFlow<List<String>>(emptyList())

    val suggest = MutableStateFlow<List<String>>(emptyList())

    private var suggestSeq = 0

    private var token = 0
    private var arriveSeq = 0
    private var semaphorePermits = HawkConfig.SEARCH_THREADS_DEFAULT
    private var semaphore = Semaphore(semaphorePermits)
    private val pendingSources = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val scope = viewModelScope

    companion object {
        private val SEARCH_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        private const val SEARCH_TIMEOUT_MS = 30_000L

        private const val DOUBAN_HOT_URL =
            "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range="

        private const val HOT_SEARCH_LIMIT = 20

        private const val SUGGEST_URL = "https://suggest.video.iqiyi.com/?if=mobile&key="

        private const val SUGGEST_LIMIT = 20

        @Volatile
        var checkedSources: HashMap<String, String>? = null
            private set

        @Volatile
        private var checkedSourcesApiUrl: String? = null

        @JvmStatic
        fun clearCheckedSources() {
            checkedSources = null
            checkedSourcesApiUrl = null
        }

        @JvmStatic
        fun loadCheckedSources() {
            val selection = SearchSettings.currentSelection()
            if (selection != null) {
                checkedSources = HashMap<String, String>().apply { selection.forEach { put(it, "1") } }
                checkedSourcesApiUrl = KV.get(HawkConfig.API_URL, "")
                return
            }
            val all = SearchHelper.getSources()
            if (all.isEmpty()) {
                checkedSources = null
                checkedSourcesApiUrl = null
                return
            }
            checkedSources = all
            checkedSourcesApiUrl = KV.get(HawkConfig.API_URL, "")
        }

        @JvmStatic
        fun isCheckedSourcesStale(): Boolean {
            if (checkedSources == null) return true
            if (checkedSourcesApiUrl != KV.get(HawkConfig.API_URL, "")) return true
            return SearchHelper.isSelectionStale(checkedSources)
        }

        /** 搜索屏蔽词:逗号/换行/空格分隔,去空,token 化。空串表示不屏蔽。 */
        @JvmStatic
        fun blockKeywords(): List<String> {
            val raw = KV.get(HawkConfig.SEARCH_BLOCK_KEYWORDS, HawkConfig.SEARCH_BLOCK_KEYWORDS_DEFAULT)
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(',', '，', '\n', ' ', '、', ';', '；')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        /** 文本是否命中任一屏蔽词(源名/标题共用) */
        @JvmStatic
        fun isBlocked(text: String?): Boolean {
            if (text.isNullOrEmpty()) return false
            val keywords = blockKeywords()
            if (keywords.isEmpty()) return false
            val lower = text.lowercase()
            return keywords.any { lower.contains(it.lowercase()) }
        }
    }

    init {
        org.greenrobot.eventbus.EventBus.getDefault().register(this)
        fetchHotSearch()
    }

    override fun onCleared() {
        org.greenrobot.eventbus.EventBus.getDefault().unregister(this)
        try {
            OkGo.getInstance().cancelTag("suggest")
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel suggest requests failed")
        }
    }

    private fun fetchHotSearch() {
        scope.launch(Dispatchers.IO) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(java.util.Date())
            val cached = KV.get(HawkConfig.HOME_HOT, "")
            if (KV.get(HawkConfig.HOME_HOT_DAY, "") == today && cached.isNotEmpty()) {
                hotSearch.value = parseHotTitles(cached)
                return@launch
            }
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            OkGo.get<String>(DOUBAN_HOT_URL + year + "," + year)
                .headers("User-Agent", UA.random())
                .execute(object : AbsCallback<String>() {
                    override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                        val body = response.body().orEmpty()
                        if (body.isNotEmpty()) {
                            KV.put(HawkConfig.HOME_HOT, body)
                            KV.put(HawkConfig.HOME_HOT_DAY, today)
                        }
                        hotSearch.value = parseHotTitles(body)
                    }

                    override fun convertResponse(response: okhttp3.Response): String =
                        response.body.string()

                    override fun onError(response: com.lzy.okgo.model.Response<String>) {
                        super.onError(response)
                        hotSearch.value = parseHotTitles(KV.get(HawkConfig.HOME_HOT, ""))
                    }
                })
        }
    }

    private fun parseHotTitles(json: String): List<String> = try {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until minOf(arr.length(), HOT_SEARCH_LIMIT))
            .mapNotNull { arr.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotEmpty() } }
    } catch (_: Throwable) {
        emptyList()
    }

    fun fetchSuggest(text: String) {
        val seq = ++suggestSeq
        OkGo.get<String>(SUGGEST_URL + java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20"))
            .tag("suggest")
            .execute(object : AbsCallback<String>() {
                override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                    if (seq != suggestSeq) return
                    suggest.value = parseSuggest(response.body().orEmpty())
                }

                override fun convertResponse(response: okhttp3.Response): String =
                    response.body.string()

                override fun onError(response: com.lzy.okgo.model.Response<String>) {
                    super.onError(response)
                }
            })
    }

    fun clearSuggest() {
        suggestSeq++
        suggest.value = emptyList()
    }

    private fun parseSuggest(json: String): List<String> = try {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until minOf(arr.length(), SUGGEST_LIMIT)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            val title = o.optString("title")
            when {
                name.isNotEmpty() -> name
                title.isNotEmpty() -> title
                else -> null
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun search(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        token = SEARCH_SEQ.incrementAndGet()
        val myToken = token
        val tokenStr = myToken.toString()
        searchedTitle.value = t
        exactMatch.value = SearchSettings.isExactMatchEnabled()
        HistoryHelper.setSearchHistory(t)
        clearSuggest()
        try {
            JsLoader.stopAll()
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "JsLoader.stopAll failed, continue new search")
        }
        try {
            OkGo.getInstance().cancelTag("search")
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel previous search requests failed")
        }
        for (entry in pendingSources) {
            entry.value.complete(Unit)
        }
        pendingSources.clear()
        val home = ApiConfig.get().getHomeSourceBean()
        val checked = checkedSources
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && (checked == null || checked.containsKey(it.key)) }
            .filter { !isBlocked(it.name) }
            .sortedBy { it.key != home.key }
        arriveSeq = 0
        results.value = sources.map { SourceResult(it.key, it.name.orEmpty(), ResultState.Pending, emptyList()) }
        sitesEmpty.value = sources.isEmpty()
        if (sources.isEmpty()) {
            running.value = false
            return
        }
        running.value = true
        scope.launch {
            coroutineScope {
                sources.map { bean ->
                    async {
                        semaphore.withPermit {
                            if (myToken != token) return@async
                            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                            pendingSources[bean.key] = done
                            try {
                                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                    withContext(Dispatchers.IO) {
                                        searchCaller.getSearch(bean.key, t, tokenStr)
                                    }
                                    done.await()
                                }
                            } finally {
                                pendingSources.remove(bean.key, done)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (myToken == token) running.value = false
        }
    }

    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    fun onSearchResultEvent(event: com.github.tvbox.osc.event.RefreshEvent) {
        if (event.type != com.github.tvbox.osc.event.RefreshEvent.TYPE_SEARCH_RESULT) return
        val data = event.obj as? AbsXml ?: return
        val myToken = token
        if (data.searchToken != myToken.toString()) return
        val sourceKey = data.sourceKey ?: return
        if (results.value.none { it.sourceKey == sourceKey }) return
        pendingSources.remove(sourceKey)?.complete(Unit)
        val videos = data.movie?.videoList.orEmpty()
            .filter { !isBlocked(it.name) }
            .filter { !exactMatch.value || SearchSettings.isExactMatch(it.name, searchedTitle.value) }
            .sortedByDescending { it.name?.trim() == searchedTitle.value }
        updateResult(sourceKey, videos)
    }

    private fun updateResult(sourceKey: String, videos: List<Movie.Video>) {
        results.value = results.value.map {
            if (it.sourceKey == sourceKey) {
                SourceResult(sourceKey, it.sourceName, ResultState.Done, videos, ++arriveSeq)
            } else {
                it
            }
        }
    }

    private val searchCaller = SourceViewModel()
}
