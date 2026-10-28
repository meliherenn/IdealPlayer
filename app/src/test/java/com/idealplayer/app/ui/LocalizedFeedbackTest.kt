package com.idealplayer.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.idealplayer.app.R
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalizedFeedbackTest {
    @Test
    fun englishCountsAndDurationsUseSingularOnlyForOne() {
        val resources = localized("en").resources
        assertThat(resources.getQuantityString(R.plurals.catalog_item_count, 0, 0)).isEqualTo("0 items")
        assertThat(resources.getQuantityString(R.plurals.catalog_item_count, 1, 1)).isEqualTo("1 item")
        assertThat(resources.getQuantityString(R.plurals.catalog_item_count, 2, 2)).isEqualTo("2 items")
        assertThat(resources.getQuantityString(R.plurals.player_minutes, 1, 1)).isEqualTo("1 minute")
        assertThat(resources.getQuantityString(R.plurals.player_minutes, 2, 2)).isEqualTo("2 minutes")
        for (count in listOf(1, 2)) {
            val unit = if (count == 1) "second" else "seconds"
            assertThat(resources.getQuantityString(R.plurals.seek_backward_feedback, count, count)).contains("$count $unit")
            assertThat(resources.getQuantityString(R.plurals.seek_forward_feedback, count, count)).contains("$count $unit")
        }
    }

    @Test
    fun turkishFeedbackAndMixedSeekStepsFormatWithoutEnglishFallback() {
        val tr = localized("tr")
        assertThat(tr.resources.getQuantityString(R.plurals.catalog_item_count, 2, 2)).isEqualTo("2 içerik")
        for (locale in listOf("tr", "en")) {
            val context = localized(locale)
            val backward = context.resources.getQuantityString(R.plurals.seek_backward_short, 1, 1)
            val forward = context.resources.getQuantityString(R.plurals.seek_forward_short, 10, 10)
            val hint = context.getString(R.string.tv_seek_controls_hint, backward, forward)
            assertThat(hint).contains(backward)
            assertThat(hint).contains(forward)
            assertThat(hint).doesNotContain("%")
        }
    }

    @Test
    fun pendingErrorsResolveAgainAfterTheAppLanguageChanges() {
        val ids = listOf(
            R.string.error_playlist_add, R.string.error_playlist_edit,
            R.string.error_playlist_unavailable, R.string.error_source_unreadable,
            R.string.error_episodes_load, R.string.catalog_sync_failed
        )
        val tr = localized("tr")
        val en = localized("en")
        ids.forEach { id ->
            assertThat(tr.getString(id)).isNotEmpty()
            assertThat(en.getString(id)).isNotEqualTo(tr.getString(id))
            assertThat(en.getString(id)).doesNotContain("http")
            assertThat(tr.getString(id)).doesNotContain("http")
        }
    }

    private fun localized(language: String): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
    }
}
