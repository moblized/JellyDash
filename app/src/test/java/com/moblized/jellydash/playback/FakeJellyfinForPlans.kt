package com.moblized.jellydash.playback

import com.moblized.jellydash.car.FakeJellyfin
import com.moblized.jellydash.jellyfin.MediaSource

/** Makes addresses easy to check in [StreamPlannerTest]. */
class FakeJellyfinForPlans : FakeJellyfin() {
    override fun directUrl(itemId: String, source: MediaSource, playSessionId: String?) = "direct:$itemId:${source.id}:$playSessionId"
    override fun signedUrl(path: String) = "signed:$path"
}
