package com.idealplayer.app.core.player

/** Exclude only the device/OS/decoder combination reproduced on physical hardware. */
internal fun isUnsafePlatformDecoder(
    mimeType: String,
    decoderName: String,
    device: String,
    sdkInt: Int
): Boolean =
    // Xiaomi 13 Ultra / Android 16: both vendor ALAC variants fail after startup.
    // The software variant invalidates its input buffer and Media3's next write
    // SIGSEGVs before Java fallback can run; the hardware variant reports an error.
    // ALAC stays available through the retained FFmpeg renderer and VLC engine.
    mimeType == "audio/alac" &&
        (decoderName == "c2.qti.alac.sw.decoder" || decoderName == "c2.qti.alac.hw.decoder") &&
        device == "ishtar" && sdkInt == 36
