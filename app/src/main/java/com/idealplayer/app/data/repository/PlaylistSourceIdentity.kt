package com.idealplayer.app.data.repository

import com.idealplayer.app.core.database.PlaylistEntity
import com.idealplayer.app.core.model.Playlist

/** Changes on activation and source edits, including an A→B→A round trip. Contains no secrets. */
data class PlaylistSourceIdentity(val playlistId: Long, val revision: Long)

internal fun PlaylistEntity.sourceIdentity() = PlaylistSourceIdentity(id, sourceRevision)
internal fun Playlist.sourceIdentity() = PlaylistSourceIdentity(id, sourceRevision)
