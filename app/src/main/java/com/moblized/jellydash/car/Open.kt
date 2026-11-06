package com.moblized.jellydash.car

import androidx.car.app.Screen
import com.moblized.jellydash.Graph
import com.moblized.jellydash.jellyfin.Browse
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Kind
import com.moblized.jellydash.jellyfin.Library
import com.moblized.jellydash.jellyfin.Sort

/**
 * Opens things in the car without going past Android Auto's limit of five screens in a row.
 * The fifth may be a details card or the player, never a list, so deeper down the app skips
 * straight to playing.
 */
object Open {
    /** Screens on the stack, the current one included. */
    private fun depth(from: Screen) = from.screenManager.stackSize

    fun item(from: Screen, item: Item) {
        val d = depth(from)
        val cc = from.carContext
        val sm = from.screenManager
        when {
            item.kind.playable -> if (d <= 3) sm.push(DetailsScreen(cc, item)) else play(from, item)
            item.kind == Kind.Series -> when {
                d <= 2 -> sm.push(SeriesScreen(cc, item, seasonsAsRows = true))
                d == 3 -> sm.push(SeriesScreen(cc, item, seasonsAsRows = false))
                else -> playSeries(from, item)
            }
            item.kind == Kind.Season -> {
                val series = item.seriesId
                if (d <= 3 && series != null) sm.push(EpisodesScreen(cc, series, item)) else playSeries(from, item)
            }
            else -> folder(from, item.name, Browse(item.id, "folders", flatten = d >= 3), item.childCount)
        }
    }

    fun library(from: Screen, library: Library, total: Int?) =
        folder(from, library.name, Browse(library.id, library.collectionType), total)

    /** A library or folder: posters straight away when they fit on one screen, A–Z groups when not. */
    private fun folder(from: Screen, title: String, where: Browse, total: Int?) {
        val d = depth(from)
        val cc = from.carContext
        val page = cc.gridPage()
        val sm = from.screenManager
        if ((total != null && total <= page) || d >= 3) {
            sm.push(GridScreen(cc, title, where, Sort.Name, 0, page))
        } else {
            sm.push(GroupsScreen(cc, title, where))
        }
    }

    fun play(from: Screen, item: Item, fromStart: Boolean = false) {
        // Tapping Play counts as "I'm parked", unless the truck was moving moments ago.
        Graph.guard().confirmParked()
        Graph.playback(from.carContext).play(item, fromStart)
        from.screenManager.push(PlayerScreen(from.carContext))
    }

    /** Plays the next episode of a show (or season) you haven't finished. */
    fun playSeries(from: Screen, show: Item) {
        Graph.guard().confirmParked()
        Graph.playback(from.carContext).playNextOf(show)
        from.screenManager.push(PlayerScreen(from.carContext))
    }
}
