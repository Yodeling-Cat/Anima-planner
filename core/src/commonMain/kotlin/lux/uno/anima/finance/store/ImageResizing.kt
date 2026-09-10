package lux.uno.anima.finance.store

/**
 * Decodes [bytes], scales it down so neither edge exceeds [maxEdge], and re-encodes as PNG.
 * Images already within the limit are still re-encoded so a 4 MB screenshot never lands in
 * the icon folder untouched. Returns null when the bytes are not a readable image.
 */
expect fun resizeImageToPng(bytes: ByteArray, maxEdge: Int): ByteArray?
