package se.swahnchat.app.media

import java.io.File
import java.io.IOException

/**
 * File names in messages are chosen by the sender (and the server controls everything around them), so they are untrusted
 * input wherever they become a path. These helpers have no Android dependencies so they can be tested on the JVM.
 */
object ShareFiles {
    private const val MAX_NAME = 100
    private val unsafe = Regex("[^\\p{L}\\p{N}._ -]")

    /**
     * A single, harmless path segment for [name]: path separators, control and bidi characters and everything else outside
     * letters, digits, `.`, `_`, ` ` and `-` become `_`; leading dots are prefixed (no hidden files, never `.` or `..`);
     * blank results fall back to [fallback]. The file extension survives truncation.
     */
    fun safeName(name: String?, fallback: String = "file"): String {
        var n = (name ?: "").replace(unsafe, "_").trim()
        if (n.startsWith(".")) n = "_$n"
        if (n.isBlank() || n.all { it == '.' || it == '_' || it == ' ' }) return fallback
        if (n.length > MAX_NAME) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 12) n.substring(dot) else ""
            n = n.take(MAX_NAME - ext.length) + ext
        }
        return n
    }

    /**
     * Copies [source] into [dir] (cleared first) under a safe version of [name] and returns the copy.
     * @throws IOException if the copy would not end up directly inside [dir]
     */
    fun copyForSharing(source: File, dir: File, name: String?): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val copy = File(dir, safeName(name, fallback = safeName(source.name)))
        if (copy.canonicalFile.parentFile != dir.canonicalFile) throw IOException("Unsafe file name")
        source.copyTo(copy, overwrite = true)
        return copy
    }
}
