package com.moblized.jellydash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.moblized.jellydash.Graph
import com.moblized.jellydash.R
import com.moblized.jellydash.jellyfin.Browse
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Kind
import com.moblized.jellydash.jellyfin.Sort
import kotlinx.coroutines.launch

/** Posters: one screenful of a library, folder or A–Z group. */
class GridScreen(
    carContext: CarContext,
    private val title: String,
    private val where: Browse,
    private val sort: Sort,
    private val start: Int,
    /** How many titles this screen covers; past one screenful, a "More" tile continues. */
    private val count: Int,
) : Screen(carContext) {
    private val jellyfin = Graph.jellyfin(carContext)
    private val posters = Posters(jellyfin, lifecycleScope, 180) { invalidate() }
    private val page = carContext.gridPage()
    private var items: List<Item>? = null
    private var error: String? = null

    /** This screen's place on the stack: it's built just before it's pushed. */
    private val depthHere = screenManager.stackSize + 1

    /** A "More" tile needs room for one more grid after this one. */
    private val canGoDeeper: Boolean = depthHere <= 3
    private val shows: Int = if (count > page && canGoDeeper) page - 1 else minOf(count, page)

    init {
        lifecycleScope.launch {
            try {
                items = jellyfin.browse(where, sort, start, shows).items
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load this"
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        error?.let {
            return MessageTemplate.Builder(it).setTitle(title).setHeaderAction(Action.BACK).build()
        }
        val b = GridTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK)
        val list = items ?: return b.setLoading(true).build()
        val l = ItemList.Builder().setNoItemsMessage("Nothing here yet")
        for (item in list) l.addItem(tile(item))
        if (count > page && canGoDeeper && list.size >= shows) {
            val next = start + list.size
            val left = count - list.size
            l.addItem(
                GridItem.Builder()
                    .setTitle("More")
                    .setText(if (left == 1) "1 more" else "$left more")
                    .setImage(carContext.icon(R.drawable.ic_next))
                    .setOnClickListener { screenManager.push(GridScreen(carContext, title, where, sort, next, left)) }
                    .build(),
            )
        }
        return b.setSingleList(l.build()).build()
    }

    private fun tile(item: Item): GridItem {
        val text = describe(item)
        val name = if (item.kind == Kind.Episode) "${item.seriesName ?: ""} ${item.name}".trim() else item.name
        return GridItem.Builder()
            .setTitle(name)
            .apply { if (text.isNotBlank()) setText(text) }
            .setImage(posters.icon(item) ?: carContext.icon(placeholderFor(item.kind)))
            .setOnClickListener { Open.item(this, item) }
            .build()
    }
}
