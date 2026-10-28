package com.idealplayer.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvFocusContractTest {

    @Test
    fun `remembered enabled child wins after a list refresh`() {
        val contract = TvFocusContract(
            initialFocusKey = "channel:1",
            restoreFocusKey = "channel:2",
            backTargetKey = "close"
        )

        assertThat(
            resolveTvFocusTarget(
                contract = contract,
                enabledKeys = setOf("channel:1", "channel:2", "channel:3", "close"),
                rememberedKey = "channel:3",
                firstEnabledKey = "channel:1"
            )
        ).isEqualTo("channel:3")
    }

    @Test
    fun `removed child restores explicit target then initial target`() {
        val contract = TvFocusContract(
            initialFocusKey = "channel:1",
            restoreFocusKey = "channel:2",
            backTargetKey = "close"
        )

        assertThat(
            resolveTvFocusTarget(
                contract = contract,
                enabledKeys = setOf("channel:1", "channel:2", "close"),
                rememberedKey = "removed"
            )
        ).isEqualTo("channel:2")
        assertThat(
            resolveTvFocusTarget(
                contract = contract,
                enabledKeys = setOf("channel:1", "close"),
                rememberedKey = "removed"
            )
        ).isEqualTo("channel:1")
    }

    @Test
    fun `empty or disabled content falls back to visible back target`() {
        val contract = TvFocusContract(
            initialFocusKey = "disabled",
            backTargetKey = "close",
            exitDirections = setOf(TvFocusDirection.LEFT)
        )

        assertThat(
            resolveTvFocusTarget(
                contract = contract,
                enabledKeys = setOf("close"),
                rememberedKey = null
            )
        ).isEqualTo("close")
        assertThat(contract.exitDirections).containsExactly(TvFocusDirection.LEFT)
    }
}
