package com.moblized.jellydash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.moblized.jellydash.Graph
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Kind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search your whole server. The keyboard only shows while parked; that's Android Auto's rule. */
class SearchScreen(carContext: CarContext) : Screen(carContext) {
    private val jellyfin = Graph.jellyfin(carContext)
    private val posters = Posters(jellyfin, lifecycleScope, 160, Graph.downloads(carContext)::posterFile) { invalidate() }
    private var text = ""
    private var results: List<Item> = emptyList()
    private var searching = false
    private var message: String? = null
    private var job: Job? = null

    private fun search(term: String, wait: Long) {
        text = term
        job?.cancel()
        if (term.trim().length < 2) {
            results = emptyList()
            message = null
            searching = false
            invalidate()
            return
        }
        job = lifecycleScope.launch {
            delay(wait)
            searching = true
            invalidate()
            try {
                results = jellyfin.search(term.trim(), carContext.listLimit())
                message = if (results.isEmpty()) "Nothing matches \"${term.trim()}\"" else null
            } catch (e: Exception) {
                results = emptyList()
                message = e.message ?: "Search didn't work"
            }
            searching = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) = search(searchText, 700)
                override fun onSearchSubmitted(searchText: String) = search(searchText, 0)
            },
        )
            .setHeaderAction(Action.BACK)
            .setSearchHint("Movies and shows")
            .setShowKeyboardByDefault(true)
        if (text.isNotEmpty()) b.setInitialSearchText(text)
        if (searching) return b.setLoading(true).build()
        val list = ItemList.Builder()
        message?.let { list.setNoItemsMessage(it) }
        for (item in results) {
            val what = when (item.kind) {
                Kind.Series -> "Show"
                Kind.Episode -> "Episode"
                Kind.Movie -> "Movie"
                else -> "Video"
            }
            val detail = describe(item)
            list.addItem(
                Row.Builder()
                    .setTitle(if (item.kind == Kind.Episode) "${headline(item)}: ${item.name}" else item.name)
                    .addText(if (detail.isBlank()) what else "$what · $detail")
                    .setImage(posters.icon(item) ?: carContext.icon(placeholderFor(item.kind)), Row.IMAGE_TYPE_LARGE)
                    .setOnClickListener { Open.item(this, item) }
                    .build(),
            )
        }
        return b.setItemList(list.build()).build()
    }
}
