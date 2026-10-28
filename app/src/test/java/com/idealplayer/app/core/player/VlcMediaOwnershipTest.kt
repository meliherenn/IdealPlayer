package com.idealplayer.app.core.player

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Test
import org.videolan.libvlc.interfaces.IMedia

class VlcMediaOwnershipTest {
    @Test fun `each retained media is released exactly once including missing tracks`() {
        val media = mockk<IMedia>(relaxed = true)
        every { media.trackCount } returns 2
        every { media.getTrack(any()) } returns null
        repeat(50) { assertThat(readRetainedMediaTracks(media)).isEmpty() }
        verify(exactly = 50) { media.release() }
    }

    @Test fun `metadata failure still releases retained media`() {
        val media = mockk<IMedia>(relaxed = true)
        every { media.trackCount } throws IllegalStateException("Synthetic metadata failure")
        assertThrows(IllegalStateException::class.java) { readRetainedMediaTracks(media) }
        verify(exactly = 1) { media.release() }
        assertThat(readRetainedMediaTracks(null)).isEmpty()
    }
}
