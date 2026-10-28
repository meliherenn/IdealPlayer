package com.idealplayer.app.ui.player

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.idealplayer.app.core.player.PlaybackVisibility
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerLifecycleBindingTest {
    private val viewModel = mockk<PlayerViewModel>(relaxed = true)

    private class Destination : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Test fun `inline disposal in a visible host releases a surviving destination owner`() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val destination = Destination().apply { lifecycle.currentState = Lifecycle.State.RESUMED }
        val binding = PlayerLifecycleBinding(viewModel, destination, host.get(), isTv = false)

        binding.dispose()

        verify(exactly = 1) { viewModel.onPlaybackVisible() }
        verify { viewModel.onPlaybackHidden(PlaybackVisibility(false, false), ownerRemoved = true) }
        clearMocks(viewModel, answers = false)
        destination.lifecycle.currentState = Lifecycle.State.CREATED
        verify(exactly = 0) { viewModel.onPlaybackHidden(any(), any()) }
        host.pause().stop().destroy()
    }

    @Test fun `Home and stopped subtree disposal keep the destination resumable`() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val destination = Destination().apply { lifecycle.currentState = Lifecycle.State.RESUMED }
        val binding = PlayerLifecycleBinding(viewModel, destination, host.get(), isTv = true)

        host.pause().stop()
        destination.lifecycle.currentState = Lifecycle.State.CREATED
        binding.dispose()

        verify(exactly = 2) { viewModel.onPlaybackHidden(PlaybackVisibility(true, false), ownerRemoved = false) }
        verify(exactly = 0) { viewModel.onPlaybackHidden(any(), ownerRemoved = true) }
        host.destroy()
    }

    @Test fun `destroyed destination releases even when its Activity is stopped`() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val destination = Destination().apply { lifecycle.currentState = Lifecycle.State.RESUMED }
        val binding = PlayerLifecycleBinding(viewModel, destination, host.get(), isTv = false)

        host.pause().stop()
        destination.lifecycle.currentState = Lifecycle.State.DESTROYED
        binding.dispose()

        verify { viewModel.onPlaybackHidden(PlaybackVisibility(false, false), ownerRemoved = true) }
        host.destroy()
    }

    @Test fun `return from Home delivers visibility once per start`() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val binding = PlayerLifecycleBinding(viewModel, host.get(), host.get(), isTv = true)

        host.pause().stop().restart().start().resume()

        verify(exactly = 2) { viewModel.onPlaybackVisible() }
        verify(exactly = 1) { viewModel.onPlaybackHidden(PlaybackVisibility(true, false), ownerRemoved = false) }
        binding.dispose()
        host.pause().stop().destroy()
    }
}
