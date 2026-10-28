package com.idealplayer.app.data.parser

/**
 * Deterministic, non-functional catalog fixtures used by parser stress tests.
 * All hosts use the reserved `.test` suffix so the generated data can never address a real
 * IPTV provider or copyrighted stream.
 */
internal object SyntheticCatalogFixtures {
    fun m3u(liveCount: Int, movieCount: Int): String = buildString(
        capacity = (liveCount + movieCount) * 150
    ) {
        appendLine("#EXTM3U")
        repeat(liveCount) { index ->
            appendLine(
                "#EXTINF:-1 tvg-id=\"live-$index\" tvg-name=\"Synthetic Live $index\" " +
                    "group-title=\"Synthetic Live\",Synthetic Live $index"
            )
            appendLine("https://streams.invalid.test/live/$index.m3u8")
        }
        repeat(movieCount) { index ->
            appendLine(
                "#EXTINF:-1 tvg-id=\"movie-$index\" group-title=\"Synthetic Movies\"," +
                    "Synthetic Movie $index"
            )
            appendLine("https://streams.invalid.test/movie/user/pass/$index.mp4")
        }
    }

    fun xmltv(programCount: Int, channelCount: Int = 100): String = buildString(
        capacity = programCount * 170
    ) {
        require(channelCount > 0)
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<tv generator-info-name=\"IdealPlayer synthetic fixture\">")
        repeat(channelCount) { channel ->
            appendLine("<channel id=\"synthetic-$channel\"><display-name>Synthetic $channel</display-name></channel>")
        }
        repeat(programCount) { index ->
            val channel = index % channelCount
            append("<programme start=\"20260419090000 +0000\" stop=\"20260419100000 +0000\" channel=\"synthetic-")
            append(channel)
            append("\"><title>Synthetic Programme ")
            append(index)
            appendLine("</title><desc>Generated test metadata</desc></programme>")
        }
        appendLine("</tv>")
    }
}
