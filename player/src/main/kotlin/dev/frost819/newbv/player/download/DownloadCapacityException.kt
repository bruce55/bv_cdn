package dev.frost819.newbv.player.download

import java.io.IOException

/** A demanded read could not obtain safe memory after the bounded recovery interval. */
internal class DownloadCapacityException(
    message: String,
) : IOException(message)
