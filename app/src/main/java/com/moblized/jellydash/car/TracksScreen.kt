package com.moblized.jellydash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import com.moblized.jellydash.Graph
import com.moblized.jellydash.R
import androidx.media3.common.C

/** Audio language and subtitles for what's playing. */
class TracksScreen(carContext: CarContext) : Screen(carContext) {
    private val playback = Graph.playback(carContext)

    override fun onGetTemplate(): Template {
        val b = ListTemplate.Builder().setTitle("Audio and subtitles").setHeaderAction(Action.BACK)
        if (playback.state.value?.offline == true) return offline(b)
        val plan = playback.state.value?.plan
            ?: return b.setSingleList(ItemList.Builder().setNoItemsMessage("Nothing is playing").build()).build()

        val check = carContext.icon(R.drawable.ic_check)
        val audio = ItemList.Builder()
        val tracks = plan.source.audio
        for (t in tracks) {
            audio.addItem(
                Row.Builder()
                    .setTitle(t.title)
                    .apply { if (t.index == plan.audioIndex) setImage(check) }
                    .setOnClickListener {
                        playback.chooseAudio(t.index)
                        screenManager.pop()
                    }
                    .build(),
            )
        }

        val subs = ItemList.Builder()
        subs.addItem(
            Row.Builder()
                .setTitle("Off")
                .apply { if (plan.subtitleIndex < 0) setImage(check) }
                .setOnClickListener {
                    playback.chooseSubtitle(-1)
                    screenManager.pop()
                }
                .build(),
        )
        for (t in plan.source.subtitles) {
            val burned = t.deliveryMethod == "Encode"
            subs.addItem(
                Row.Builder()
                    .setTitle(t.title)
                    .apply {
                        if (t.index == plan.subtitleIndex) setImage(check)
                        if (burned) addText("Drawn into the picture by your server")
                    }
                    .setOnClickListener {
                        playback.chooseSubtitle(t.index)
                        screenManager.pop()
                    }
                    .build(),
            )
        }

        if (tracks.size > 1) b.addSectionedList(SectionedItemList.create(audio.build(), "Audio"))
        b.addSectionedList(SectionedItemList.create(subs.build(), "Subtitles"))
        return b.build()
    }

    /** A download: the tracks inside the file, switched right on the phone. */
    private fun offline(b: ListTemplate.Builder): Template {
        val check = carContext.icon(R.drawable.ic_check)
        val all = playback.localTracks()
        fun row(title: String, selected: Boolean, pick: () -> Unit) = Row.Builder()
            .setTitle(title)
            .apply { if (selected) setImage(check) }
            .setOnClickListener {
                pick()
                screenManager.pop()
            }
            .build()
        val audio = all.filter { it.type == C.TRACK_TYPE_AUDIO }
        val text = all.filter { it.type == C.TRACK_TYPE_TEXT }
        if (audio.size > 1) {
            val l = ItemList.Builder()
            audio.forEach { t -> l.addItem(row(t.label, t.selected) { playback.chooseLocal(t) }) }
            b.addSectionedList(SectionedItemList.create(l.build(), "Audio"))
        }
        val s = ItemList.Builder()
        s.addItem(row("Off", text.none { it.selected }) { playback.localSubtitlesOff() })
        text.forEach { t -> s.addItem(row(t.label, t.selected) { playback.chooseLocal(t) }) }
        b.addSectionedList(SectionedItemList.create(s.build(), "Subtitles"))
        return b.build()
    }
}
