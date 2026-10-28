package com.idealplayer.app

/**
 * Leanback launcher entry point. UI selection remains centralized in [MainActivity], while this
 * concrete activity lets the TV manifest declare a landscape-only contract without locking phone
 * and tablet users to landscape.
 */
class TvMainActivity : MainActivity()
