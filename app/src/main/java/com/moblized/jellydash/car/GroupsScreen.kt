package com.moblized.jellydash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.moblized.jellydash.Graph
import com.moblized.jellydash.R
import com.moblized.jellydash.jellyfin.Browse
import com.moblized.jellydash.jellyfin.Sort
import kotlinx.coroutines.launch

/** A big library split into A–Z groups, so every title is a couple of taps away. */
class GroupsScreen(carContext: CarContext, private val title: String, private val where: Browse) : Screen(carContext) {
    private val jellyfin = Graph.jellyfin(carContext)
    private var groups: List<Group>? = null
    private var error: String? = null

    init {
        lifecycleScope.launch {
            try {
                val names = jellyfin.sortNames(where)
                groups = Groups.split(names, carContext.gridPage(), carContext.listLimit() - 1)
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load this library"
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        error?.let {
            return MessageTemplate.Builder(it).setTitle(title).setHeaderAction(Action.BACK).build()
        }
        val b = ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK)
        val g = groups ?: return b.setLoading(true).build()
        val page = carContext.gridPage()
        val list = ItemList.Builder().setNoItemsMessage("Nothing here yet")
        if (g.isNotEmpty()) {
            list.addItem(
                Row.Builder()
                    .setTitle("Recently added")
                    .setImage(carContext.icon(R.drawable.ic_new))
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(GridScreen(carContext, "$title · New", where, Sort.Newest, 0, page)) }
                    .build(),
            )
        }
        for (group in g) {
            list.addItem(
                Row.Builder()
                    .setTitle(group.label)
                    .addText(if (group.count == 1) "1 title" else "${group.count} titles")
                    .setImage(carContext.icon(R.drawable.ic_list))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(GridScreen(carContext, "$title · ${group.label}", where, Sort.Name, group.start, group.count))
                    }
                    .build(),
            )
        }
        return b.setSingleList(list.build()).build()
    }
}
