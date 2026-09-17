package com.nuvio.tv.ui.screens.player

import java.util.concurrent.atomic.AtomicInteger

// The analytics diagnostics hold their underrun fields as plain vars written from the analytics
// thread, so the overlay counts here rather than sampling those across threads every second.
internal object PlayerAudioUnderrunCounter {
    private val count = AtomicInteger(0)
    private val iecTotal = AtomicInteger(0)

    fun reset() {
        count.set(0)
        iecTotal.set(0)
    }

    fun record() {
        count.incrementAndGet()
    }

    /** Latest underrun total of the IEC track; the HUD shows whichever path is larger. */
    fun recordIec(total: Int) = iecTotal.set(total)

    fun current(): Int = maxOf(count.get(), iecTotal.get())
}
