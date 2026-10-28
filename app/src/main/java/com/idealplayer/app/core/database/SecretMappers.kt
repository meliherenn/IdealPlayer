package com.idealplayer.app.core.database

import com.idealplayer.app.core.model.Channel
import com.idealplayer.app.core.model.Episode
import com.idealplayer.app.core.model.Movie
import com.idealplayer.app.core.model.Playlist
import com.idealplayer.app.core.model.Series
import com.idealplayer.app.core.model.WatchHistoryItem
import com.idealplayer.app.core.security.SecretEnvelopeCodec

fun PlaylistEntity.toModel(codec: SecretEnvelopeCodec): Playlist = toModel().copy(
    url = codec.decryptOrEmpty(url),
    serverUrl = codec.decryptOrEmpty(serverUrl),
    username = codec.decryptOrEmpty(username),
    password = codec.decryptOrEmpty(password),
    epgUrl = codec.decryptOrEmpty(epgUrl)
)

fun Playlist.toSecureEntity(codec: SecretEnvelopeCodec): PlaylistEntity = toEntity().copy(
    url = codec.encrypt(url),
    serverUrl = codec.encrypt(serverUrl),
    username = codec.encrypt(username),
    password = codec.encrypt(password),
    epgUrl = codec.encrypt(epgUrl)
)

fun PlaylistEntity.encryptSecrets(codec: SecretEnvelopeCodec): PlaylistEntity = copy(
    url = codec.encrypt(url), serverUrl = codec.encrypt(serverUrl), username = codec.encrypt(username),
    password = codec.encrypt(password), epgUrl = codec.encrypt(epgUrl)
)

fun ChannelEntity.toModel(codec: SecretEnvelopeCodec): Channel = toModel().copy(
    streamUrl = codec.decryptOrEmpty(streamUrl),
    catchupSource = codec.decryptOrEmpty(catchupSource)
)

fun ChannelEntity.encryptSecrets(codec: SecretEnvelopeCodec): ChannelEntity = copy(
    streamUrl = codec.encrypt(streamUrl),
    catchupSource = codec.encrypt(catchupSource)
)

@JvmName("encryptChannelBatch")
fun List<ChannelEntity>.encryptSecrets(codec: SecretEnvelopeCodec): List<ChannelEntity> {
    val encrypted = codec.encryptBatch(flatMap { listOf(it.streamUrl, it.catchupSource) })
    return mapIndexed { index, row -> row.copy(streamUrl = encrypted[index * 2], catchupSource = encrypted[index * 2 + 1]) }
}

@JvmName("encryptMovieBatch")
fun List<MovieEntity>.encryptSecrets(codec: SecretEnvelopeCodec): List<MovieEntity> {
    val encrypted = codec.encryptBatch(map { it.streamUrl })
    return mapIndexed { index, row -> row.copy(streamUrl = encrypted[index]) }
}

@JvmName("encryptEpisodeBatch")
fun List<EpisodeEntity>.encryptSecrets(codec: SecretEnvelopeCodec): List<EpisodeEntity> {
    val encrypted = codec.encryptBatch(map { it.streamUrl })
    return mapIndexed { index, row -> row.copy(streamUrl = encrypted[index]) }
}

fun ChannelEntity.decryptSecrets(codec: SecretEnvelopeCodec): ChannelEntity = copy(
    streamUrl = codec.decryptOrEmpty(streamUrl),
    catchupSource = codec.decryptOrEmpty(catchupSource)
)

fun MovieEntity.toModel(codec: SecretEnvelopeCodec): Movie = toModel().copy(
    streamUrl = codec.decryptOrEmpty(streamUrl)
)

fun MovieEntity.encryptSecrets(codec: SecretEnvelopeCodec): MovieEntity = copy(
    streamUrl = codec.encrypt(streamUrl)
)

fun MovieEntity.decryptSecrets(codec: SecretEnvelopeCodec): MovieEntity = copy(
    streamUrl = codec.decryptOrEmpty(streamUrl)
)

fun SeriesEntity.toModel(codec: SecretEnvelopeCodec): Series = toModel()

fun EpisodeEntity.toModel(codec: SecretEnvelopeCodec): Episode = toModel().copy(
    streamUrl = codec.decryptOrEmpty(streamUrl)
)

fun EpisodeEntity.encryptSecrets(codec: SecretEnvelopeCodec): EpisodeEntity = copy(
    streamUrl = codec.encrypt(streamUrl)
)

fun EpisodeEntity.decryptSecrets(codec: SecretEnvelopeCodec): EpisodeEntity = copy(
    streamUrl = codec.decryptOrEmpty(streamUrl)
)

fun WatchHistoryEntity.toModel(codec: SecretEnvelopeCodec): WatchHistoryItem = toModel().copy(
    streamUrl = codec.decryptOrEmpty(streamUrl)
)

fun WatchHistoryItem.toSecureEntity(codec: SecretEnvelopeCodec): WatchHistoryEntity =
    toEntity().copy(streamUrl = codec.encrypt(streamUrl))

fun FavoriteEntity.encryptSecrets(codec: SecretEnvelopeCodec): FavoriteEntity = copy(
    streamUrl = codec.encrypt(streamUrl)
)

fun FavoriteEntity.decryptSecrets(codec: SecretEnvelopeCodec): FavoriteEntity = copy(
    streamUrl = codec.decryptOrEmpty(streamUrl)
)
