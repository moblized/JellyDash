package com.moblized.jellydash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.moblized.jellydash.Graph
import com.moblized.jellydash.jellyfin.Item
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A show: the next episode to watch, then its seasons. Deeper in the app, where there's no room for
 * one more list, every episode is listed here by season instead.
 */
class SeriesScreen(carContext: CarContext, private val show: Item, private val seasonsAsRows: Boolean) : Screen(carContext) {
    private val jellyfin = Graph.jellyfin(carContext)
    private val posters = Posters(jellyfin, lifecycleScope, 160, Graph.downloads(carContext)::posterFile) { invalidate() }
    private var loaded = false
    private var error: String? = null
    private var next: Item? = null
    private var seasons: List<Item> = emptyList()
    private var episodes: List<Item> = emptyList()

    init {
        lifecycleScope.launch {
            try {
                coroutineScope {
                    val n = async { runCatching { jellyfin.nextUpFor(show.id) }.getOrNull() }
                    if (seasonsAsRows) seasons = jellyfin.seasons(show.id) else episodes = jellyfin.episodes(show.id, null)
                    next = n.await()
                }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load this show"
            }
            loaded = true
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        error?.let {
            return MessageTemplate.Builder(it).setTitle(show.name).setHeaderAction(Action.BACK).build()
        }
        val b = ListTemplate.Builder().setTitle(show.name).setHeaderAction(Action.BACK)
        if (!loaded) return b.setLoading(true).build()
        var budget = carContext.listLimit()
        var any = false
        fun section(title: String, rows: List<Row>) {
            val take = rows.take(budget)
            if (take.isEmpty()) return
            budget -= take.size
            any = true
            b.addSectionedList(SectionedItemList.create(ItemList.Builder().apply { take.forEach { addItem(it) } }.build(), title))
        }
        next?.let { section("Up next", listOf(episodeRow(it, withNumber = true))) }
        if (seasonsAsRows) {
            section(
                "Seasons",
                seasons.map { s ->
                    Row.Builder()
                        .setTitle(s.name)
                        .apply { describe(s).takeIf { it.isNotBlank() }?.let { addText(it) } }
                        .setImage(posters.icon(s) ?: carContext.icon(placeholderFor(s.kind)), Row.IMAGE_TYPE_LARGE)
                        .setBrowsable(true)
                        .setOnClickListener { Open.item(this, s.copy(seriesId = s.seriesId ?: show.id, seriesName = s.seriesName ?: show.name)) }
                        .build()
                },
            )
        } else {
            for ((season, eps) in episodes.groupBy { it.parentIndex }) {
                section(if (season == null) "Specials" else "Season $season", eps.map { episodeRow(it, withNumber = false) })
            }
        }
        if (!any) return b.setSingleList(ItemList.Builder().setNoItemsMessage("No episodes yet").build()).build()
        return b.build()
    }

    private fun episodeRow(ep: Item, withNumber: Boolean): Row {
        val num = ep.index?.let { "$it. " } ?: ""
        val text = if (withNumber) describe(ep) else describe(ep).substringAfter(" · ", "")
        return Row.Builder()
            .setTitle(num + ep.name)
            .apply { if (text.isNotBlank()) addText(text) }
            .setImage(posters.icon(ep) ?: carContext.icon(placeholderFor(ep.kind)), Row.IMAGE_TYPE_LARGE)
            .setOnClickListener { Open.play(this, ep) }
            .build()
    }
}

/** One season's episodes. Tapping one plays it, picking up where you left off. */
class EpisodesScreen(carContext: CarContext, private val seriesId: String, private val season: Item) : Screen(carContext) {
    private val jellyfin = Graph.jellyfin(carContext)
    private val posters = Posters(jellyfin, lifecycleScope, 160, Graph.downloads(carContext)::posterFile) { invalidate() }
    private var episodes: List<Item>? = null
    private var error: String? = null
    private val title = season.seriesName?.let { "$it · ${season.name}" } ?: season.name

    init {
        lifecycleScope.launch {
            try {
                episodes = jellyfin.episodes(seriesId, season.id)
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load this season"
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        error?.let {
            return MessageTemplate.Builder(it).setTitle(title).setHeaderAction(Action.BACK).build()
        }
        val b = ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK)
        val eps = episodes ?: return b.setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage("No episodes yet")
        for (ep in eps.take(carContext.listLimit())) {
            val text = describe(ep).substringAfter(" · ", "")
            list.addItem(
                Row.Builder()
                    .setTitle((ep.index?.let { "$it. " } ?: "") + ep.name)
                    .apply { if (text.isNotBlank()) addText(text) }
                    .setImage(posters.icon(ep) ?: carContext.icon(placeholderFor(ep.kind)), Row.IMAGE_TYPE_LARGE)
                    .setOnClickListener { Open.play(this, ep) }
                    .build(),
            )
        }
        return b.setSingleList(list.build()).build()
    }
}
