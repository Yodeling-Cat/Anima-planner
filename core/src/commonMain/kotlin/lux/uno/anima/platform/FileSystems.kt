package lux.uno.anima.platform

import okio.FileSystem

/**
 * okio only exposes `FileSystem.SYSTEM` per platform, not from common code, so each target
 * hands it over through here.
 */
expect fun platformFileSystem(): FileSystem
