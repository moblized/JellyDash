package com.moblized.jellydash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.moblized.jellydash.Graph
import com.moblized.jellydash.R
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Kind
import com.moblized.jellydash.jellyfin.Ticks
import kotlinx.coroutines.launch

/** A movie or episode: what it is, and Resume or Play. */
class DetailsScreen(carContext: CarContext, private val start: Item) : Screen(carContext) {
    private val jellyfin = Graph.jellyfin(carContext)
    private val posters = Posters(jellyfin, lifecycleScope, 300) { invalidate() }
    private var item: Item? = null

    init {
        lifecycleScope.launch {
            // Fresh from the server, so the resume point is the latest one.
            item = runCatching { jellyfin.item(start.id) }.getOrNull() ?: start
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val title = if (start.kind == Kind.Episode) headline(start) else start.name
        val cur = item ?: return PaneTemplate.Builder(Pane.Builder().setLoading(true).build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()
        val pane = Pane.Builder()
        val facts = facts(cur)
        pane.addRow(
            Row.Builder()
                .setTitle(if (cur.kind == Kind.Episode) episodeTitle(cur) else facts.ifBlank { cur.name })
                .apply {
                    if (cur.kind == Kind.Episode && facts.isNotBlank()) addText(facts)
                    cur.overview?.let { o -> addText(o.take(400)) }
                }
                .build(),
        )
        val run = cur.runTimeMs
        if (cur.inProgress && run != null) {
            pane.addRow(
                Row.Builder()
                    .setTitle("${Ticks.duration(run - cur.positionMs)} left")
                    .addText("You stopped at ${Ticks.clock(cur.positionMs)}")
                    .build(),
            )
        }
        val primary = Action.Builder()
            .setTitle(if (cur.inProgress) "Resume" else "Play")
            .setIcon(carContext.icon(R.drawable.ic_play))
            .setOnClickListener { Open.play(this, cur) }
        if (carContext.apiLevel() >= 4) primary.setFlags(Action.FLAG_PRIMARY) else primary.setBackgroundColor(CarColor.BLUE)
        pane.addAction(primary.build())
        if (cur.inProgress || cur.played) {
            pane.addAction(
                Action.Builder()
                    .setTitle("From the start")
                    .setIcon(carContext.icon(R.drawable.ic_restart))
                    .setOnClickListener { Open.play(this, cur, fromStart = true) }
                    .build(),
            )
        }
        if (carContext.apiLevel() >= 4) posters.icon(cur)?.let { icon -> pane.setImage(icon) }
        return PaneTemplate.Builder(pane.build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun episodeTitle(ep: Item): String {
        val s = ep.parentIndex
        val e = ep.index
        val code = if (s != null && e != null) "S${s}E$e · " else ""
        return code + ep.name
    }

    /** "2019 · PG-13 · 1 hr 52 min · ★ 7.4" */
    private fun facts(item: Item): String = listOfNotNull(
        item.year?.toString()?.takeIf { item.kind != Kind.Episode },
        item.officialRating,
        item.runTimeMs?.let { Ticks.duration(it) },
        item.communityRating?.let { "★ %.1f".format(it) },
    ).joinToString(" · ")
}
