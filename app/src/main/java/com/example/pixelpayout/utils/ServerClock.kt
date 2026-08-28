package com.example.pixelpayout.utils

import android.os.SystemClock

/**
 * The server's clock, as best the client can tell.
 *
 * Every deadline in this app is issued by the server - a buff's expiry, the
 * daily quiz reset, and (soon) streak windows and redemption unlocks. Checking
 * those against the DEVICE clock is wrong twice over: a user whose clock runs
 * fast loses time they are entitled to, and one whose clock runs slow appears
 * to keep it. It is not a theoretical problem - a test device three days ahead
 * hid an active buff completely, because a timestamp ten minutes in the
 * server's future was three days in the device's past.
 *
 * The offset is measured against [SystemClock.elapsedRealtime], which counts
 * from boot and cannot be changed, rather than against wall-clock time. That
 * is the part that matters: if the offset were anchored to
 * System.currentTimeMillis(), changing the device clock AFTER a sync would
 * corrupt it just as badly as having no sync at all.
 *
 * Until a sync happens this falls back to device time, which is no worse than
 * the behaviour it replaces. Callers do not need to care - there is no
 * "unsynced" case to handle, only a less accurate answer.
 *
 * Deliberately not a source of truth for anything that matters. The server
 * re-checks every deadline it enforces; this only decides what the UI shows.
 */
object ServerClock {

    /**
     * serverNow - elapsedRealtime, captured at the last sync. Null until then.
     * Volatile because it is written from a Firebase callback and read from
     * the main thread on every timer tick.
     */
    @Volatile
    private var offsetMillis: Long? = null

    /** True once a server timestamp has been seen this session. */
    val isSynced: Boolean
        get() = offsetMillis != null

    /**
     * Records a timestamp the server produced. Cheap enough to call from
     * anywhere a response happens to carry one.
     */
    fun sync(serverTimeMillis: Long) {
        offsetMillis = serverTimeMillis - SystemClock.elapsedRealtime()
    }

    /** The server's current time in epoch millis, or device time if unsynced. */
    fun now(): Long {
        val offset = offsetMillis ?: return System.currentTimeMillis()
        return offset + SystemClock.elapsedRealtime()
    }
}
