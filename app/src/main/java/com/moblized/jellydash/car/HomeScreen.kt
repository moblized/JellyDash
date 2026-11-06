package com.moblized.jellydash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.moblized.jellydash.Graph
import com.moblized.jellydash.R
import com.moblized.jellydash.jellyfin.Browse
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Library
import com.moblized.jellydash.jellyfin.Sort
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The first screen in the car: what you were watching, what's next, and your libraries. */
class HomeScreen(carContext: CarContext) : Screen(carContext) {
    private val jellyfin = Graph.jellyfin(carContext)
    private val prefs = Graph.prefs(carContext)
    private val posters = Posters(jellyfin, lifecycleScope, 160) { invalidate() }

    private var loading = true
    private var error: String? = null
    private var resume: List<Item> = emptyList()
    private var nextUp: List<Item> = emptyList()
    private var libraries: List<Library> = emptyList()
    private val totals = HashMap<String, Int>()

    init {
        load()
        // Signing in or out on the phone updates the car straight away.
        lifecycleScope.launch { prefs.accountFlow.drop(1).collect { load() } }
    }

    private fun load() {
        if (prefs.currentAccount == null) {
            loading = false
            invalidate()
            return
        }
        loading = true
        error = null
        invalidate()
        lifecycleScope.launch {
            try {
                coroutineScope {
                    val r = async { runCatching { jellyfin.resume(5) }.getOrDefault(emptyList()) }
                    val n = async { runCatching { jellyfin.nextUp(5) }.getOrDefault(emptyList()) }
                    val libs = jellyfin.libraries()
                    // How big each library is decides whether it opens as posters or A–Z groups.
                    libs.map { lib ->
                        async {
                            runCatching { jellyfin.browse(Browse(lib.id, lib.collectionType), Sort.Name, 0, 0).total }
                                .getOrNull()?.let { totals[lib.id] = it }
                        }
                    }.awaitAll()
                    libraries = libs
                    resume = r.await()
                    nextUp = n.await().filter { up -> resume.none { it.id == up.id } }
                }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load your libraries"
            }
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val account = prefs.currentAccount
        if (account == null) {
            return MessageTemplate.Builder("Open JellyDash on your phone and sign in to your Jellyfin server.")
                .setTitle("Sign in on your phone")
                .setHeaderAction(Action.APP_ICON)
                .setIcon(carContext.icon(R.drawable.ic_tv))
                .addAction(Action.Builder().setTitle("Try again").setOnClickListener { load() }.build())
                .build()
        }
        error?.let { msg ->
            return MessageTemplate.Builder(msg)
                .setTitle(account.serverName)
                .setHeaderAction(Action.APP_ICON)
                .addAction(Action.Builder().setTitle("Try again").setOnClickListener { load() }.build())
                .build()
        }
        val b = ListTemplate.Builder()
            .setTitle(account.serverName)
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(carContext.icon(R.drawable.ic_search))
                            .setOnClickListener { screenManager.push(SearchScreen(carContext)) }
                            .build(),
                    )
                    .build(),
            )
        if (loading) return b.setLoading(true).build()

        var budget = carContext.listLimit()
        fun section(title: String, rows: List<Row>) {
            val take = rows.take(budget)
            if (take.isEmpty()) return
            budget -= take.size
            b.addSectionedList(SectionedItemList.create(ItemList.Builder().apply { take.forEach { addItem(it) } }.build(), title))
        }
        section("Continue watching", resume.map { playRow(it) })
        section("Next up", nextUp.map { playRow(it) })
        val libRows = libraries.map { lib ->
            val total = totals[lib.id]
            Row.Builder()
                .setTitle(lib.name)
                .apply { if (total != null) addText(if (total == 1) "1 title" else "$total titles") }
                .setImage(carContext.icon(libraryIcon(lib)))
                .setBrowsable(true)
                .setOnClickListener { Open.library(this, lib, total) }
                .build()
        }
        if (libRows.isEmpty() && resume.isEmpty() && nextUp.isEmpty()) {
            return b.setSingleList(ItemList.Builder().setNoItemsMessage("No video libraries on this server").build()).build()
        }
        section("Libraries", libRows)
        return b.build()
    }

    private fun playRow(item: Item): Row {
        val sub = if (item.kind == com.moblized.jellydash.jellyfin.Kind.Episode) "${describe(item)} · ${item.name}" else describe(item)
        return Row.Builder()
            .setTitle(headline(item))
            .apply { if (sub.isNotBlank()) addText(sub) }
            .setImage(posters.icon(item) ?: carContext.icon(placeholderFor(item.kind)), Row.IMAGE_TYPE_LARGE)
            .setOnClickListener { Open.play(this, item) }
            .build()
    }

    private fun libraryIcon(lib: Library) = when (lib.collectionType) {
        "movies" -> R.drawable.ic_movie
        "tvshows" -> R.drawable.ic_tv
        "boxsets" -> R.drawable.ic_collection
        else -> R.drawable.ic_folder
    }
}
