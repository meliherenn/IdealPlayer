package com.idealplayer.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtworkDecodeBoundsTest {

    @Test
    fun cardDecodeBoundsStayBelowBackdropBounds() {
        val tvCard = artworkDecodeBounds(isTvLayout = true, ArtworkDecodeClass.Card)
        val tvBackdrop = artworkDecodeBounds(isTvLayout = true, ArtworkDecodeClass.Backdrop)
        val mobileCard = artworkDecodeBounds(isTvLayout = false, ArtworkDecodeClass.Card)
        val mobileBackdrop = artworkDecodeBounds(isTvLayout = false, ArtworkDecodeClass.Backdrop)

        assertThat(tvCard).isEqualTo(ArtworkDecodeBounds(640, 960))
        assertThat(tvBackdrop).isEqualTo(ArtworkDecodeBounds(1280, 720))
        assertThat(mobileCard).isEqualTo(ArtworkDecodeBounds(512, 768))
        assertThat(mobileBackdrop).isEqualTo(ArtworkDecodeBounds(960, 540))
        assertThat(tvCard.widthPx * tvCard.heightPx)
            .isLessThan(tvBackdrop.widthPx * tvBackdrop.heightPx)
    }
}
