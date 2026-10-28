package com.idealplayer.app.ui.player

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.idealplayer.app.core.player.PlaybackVisibility

/** Surface-independent adapter shared by fullscreen TV, mobile and inline tablet playback. */
@Composable
internal fun PlayerLifecycleEffect(viewModel: PlayerViewModel, activity: Activity?, isTv: Boolean) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, owner, activity, isTv) {
        val binding = PlayerLifecycleBinding(viewModel, owner, activity, isTv)
        onDispose { binding.dispose() }
    }
}

/** Also used when an inline player disappears while its navigation ViewModel stays alive. */
internal class PlayerLifecycleBinding(
    private val viewModel: PlayerViewModel,
    private val owner: LifecycleOwner,
    private val activity: Activity?,
    private val isTv: Boolean
) : LifecycleEventObserver {
    init {
        // Adding an observer to a started owner delivers ON_START immediately.
        owner.lifecycle.addObserver(this)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> viewModel.onPlaybackVisible()
            Lifecycle.Event.ON_STOP -> viewModel.onPlaybackHidden(visibility())
            else -> Unit
        }
    }

    fun dispose() {
        owner.lifecycle.removeObserver(this)
        val hostLifecycle = (activity as? LifecycleOwner)?.lifecycle ?: owner.lifecycle
        // A removed subtree in a visible Activity has lost its playback owner, even when its
        // nav entry survives. A stopped Activity may restore that subtree when brought back.
        // The ViewModel's policy separately preserves configuration changes and valid PiP.
        val ownerRemoved = activity?.isFinishing == true ||
            owner.lifecycle.currentState == Lifecycle.State.DESTROYED ||
            hostLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        viewModel.onPlaybackHidden(visibility(), ownerRemoved = ownerRemoved)
    }

    private fun visibility() = PlaybackVisibility(
        isTv = isTv,
        isVisible = false,
        isInPictureInPicture = activity?.isInPictureInPictureMode == true,
        isChangingConfigurations = activity?.isChangingConfigurations == true
    )
}
