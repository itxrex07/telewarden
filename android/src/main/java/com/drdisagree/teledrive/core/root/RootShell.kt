package com.drdisagree.teledrive.core.root

import java.io.File

/** Small, fixed-purpose root bridge used only by the folder picker. */
object RootShell {
    fun isAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() == 0 && output.contains("uid=0")
    }.getOrDefault(false)

    fun listDirectories(path: String): List<File>? {
        val safePath = path.takeIf { it.startsWith("/") && !it.contains('\u0000') } ?: return null
        val command = "find ${shellQuote(safePath)} -mindepth 1 -maxdepth 1 -type d -print"
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readLines() }
            if (process.waitFor() != 0) return@runCatching null
            output.map { File(it) }.filter { it.isAbsolute && it.path != safePath }
        }.getOrNull()
    }

    fun createDirectory(parent: String, name: String): Boolean {
        if (!parent.startsWith("/") || name.isBlank() || name.contains('/') || name.contains('\u0000')) return false
        val command = "mkdir -p ${shellQuote(File(parent, name).path)}"
        return runCatching {
            val process = ProcessBuilder("su", "-c", command).start()
            process.inputStream.close()
            process.errorStream.close()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
