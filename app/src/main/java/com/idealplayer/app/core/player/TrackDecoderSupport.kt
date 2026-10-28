package com.idealplayer.app.core.player

import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/** Do not confirm video-only success when the source has audio but no audio decoder.
 * A source with no audio at all is valid. An unsupported alternate track does not reject a
 * usable primary track, and codecs exceeding advertised limits may still be attempted.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun unsupportedPrimaryTrackType(tracks: Tracks): Int? =
    listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO).firstOrNull { type ->
        val groups = tracks.groups.filter { it.type == type && it.length > 0 }
        groups.isNotEmpty() && groups.none { group ->
            (0 until group.length).any { group.isTrackSupported(it, true) }
        }
    }
