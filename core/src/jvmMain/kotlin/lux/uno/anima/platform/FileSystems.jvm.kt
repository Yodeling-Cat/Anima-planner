package lux.uno.anima.platform

import okio.FileSystem

actual fun platformFileSystem(): FileSystem = FileSystem.SYSTEM
