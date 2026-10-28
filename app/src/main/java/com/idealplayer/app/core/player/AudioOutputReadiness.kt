package com.idealplayer.app.core.player

/** A decoder can discard invalid packets without raising a player error. Do not confuse
 * advancing video with decoded audio. Only repeated discards during initial playback fail;
 * network buffering, a delayed audio track and a paused player are not decoder failures.
 */
internal class AudioOutputReadiness {
    var hasRenderedOutput = false
        private set
    private var discardingSinceMs: Long? = null
    private var renderedBaseline = 0
    private var skippedBaseline = 0
    private var decoderIdentity: Any? = null

    fun observe(rendered: Int, skipped: Int, playing: Boolean, nowMs: Long, decoder: Any? = null): Boolean {
        if (decoder !== decoderIdentity) {
            decoderIdentity = decoder
            renderedBaseline = 0
            skippedBaseline = 0
            discardingSinceMs = null
        }
        // A reused decoder may retain counters; a replacement decoder starts from zero.
        if (rendered < renderedBaseline) renderedBaseline = 0
        if (skipped < skippedBaseline) skippedBaseline = 0
        if (playing && rendered > renderedBaseline) hasRenderedOutput = true
        if (hasRenderedOutput || !playing || skipped - skippedBaseline < 32) {
            discardingSinceMs = null
            return false
        }
        val since = discardingSinceMs ?: nowMs.also { discardingSinceMs = it }
        return nowMs - since >= 3_000L
    }

    fun reset(rendered: Int = 0, skipped: Int = 0, decoder: Any? = null) {
        hasRenderedOutput = false
        discardingSinceMs = null
        renderedBaseline = rendered
        skippedBaseline = skipped
        decoderIdentity = decoder
    }
}
