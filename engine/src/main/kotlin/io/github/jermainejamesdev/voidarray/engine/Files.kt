package io.github.jermainejamesdev.voidarray.engine

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A file the user chose to send. Implementations must report an exact [size]. */
interface FileHandle {
    val name: String
    val size: Long

    /** Opens the content positioned at [offset] bytes. */
    fun open(offset: Long = 0): InputStream
}

/**
 * Where received files go. Incoming data is written to a hidden partial file and only renamed to
 * its real name once complete, so an interrupted transfer never leaves a truncated file that looks valid.
 */
interface DestinationFolder {
    val label: String

    /** Sizes of the files among [names] that already exist, looked up in a single pass. */
    fun existingSizes(names: Collection<String>): Map<String, Long>

    /** Opens [partialName] for writing. Offset 0 starts a fresh file; otherwise appends at [offset]. */
    fun openPartial(partialName: String, offset: Long): OutputStream

    /** Renames the finished partial to [desiredName], or a numbered variant if taken. Returns the name used. */
    fun commit(partialName: String, desiredName: String): String

    fun discard(partialName: String)
}

class LocalFileHandle(private val file: File) : FileHandle {
    override val name: String = file.name
    override val size: Long = file.length()

    override fun open(offset: Long): InputStream {
        val stream = FileInputStream(file)
        if (offset > 0) stream.channel.position(offset)
        return stream
    }
}

class LocalDestinationFolder(val directory: File) : DestinationFolder {
    override val label: String = directory.absolutePath

    override fun existingSizes(names: Collection<String>): Map<String, Long> =
        names.mapNotNull { name ->
            File(directory, name).takeIf { it.isFile }?.let { name to it.length() }
        }.toMap()

    override fun openPartial(partialName: String, offset: Long): OutputStream {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create folder $directory")
        }
        val file = File(directory, partialName)
        if (offset == 0L) return FileOutputStream(file, false)
        if (file.length() != offset) {
            throw IOException("Partial file is ${file.length()} bytes, expected $offset")
        }
        return FileOutputStream(file, true)
    }

    override fun commit(partialName: String, desiredName: String): String {
        val source = File(directory, partialName).toPath()
        // Retried because another writer can claim the name between the exists() check and the move.
        repeat(5) {
            val finalName = uniqueName(desiredName) { File(directory, it).exists() }
            val target = File(directory, finalName).toPath()
            try {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(source, target)
                }
                return finalName
            } catch (_: java.nio.file.FileAlreadyExistsException) {
            }
        }
        throw IOException("Could not find a free name for $desiredName")
    }

    override fun discard(partialName: String) {
        File(directory, partialName).delete()
    }
}

fun InputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            // skip() may legitimately return 0 without being at EOF; read() tells the two apart.
            if (read() == -1) throw EOFException("Stream ended $remaining bytes before offset $count")
            remaining--
        }
    }
}

private val windowsReservedNames = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
)

/**
 * Reduces a peer-supplied name to a single safe path segment. The name comes from the network, so
 * anything that could escape the destination folder or is invalid on Windows or Android is removed.
 */
fun sanitizeFileName(raw: String): String {
    var name = raw.substringAfterLast('/').substringAfterLast('\\')
    name = stripInvisible(name).filterNot { it in "<>:\"|?*" }
    // Windows silently strips trailing dots and spaces, which would make two different names collide.
    name = name.trim().trimEnd('.', ' ')
    if (name.isEmpty() || name == "." || name == "..") name = "file"
    if (name.substringBefore('.').uppercase() in windowsReservedNames) name = "_$name"
    // A finished file must never be mistaken for, or collide with, one of the receiver's own partials.
    if (name.startsWith(PARTIAL_PREFIX)) name = "_$name"
    if (name.length > 200) {
        val ext = name.substringAfterLast('.', "").take(20)
        name = if (ext.isEmpty()) name.take(200) else name.take(199 - ext.length) + "." + ext
    }
    return name
}

/** Returns [desired], or "name (n).ext" for the first n that [exists] reports as free. */
fun uniqueName(desired: String, exists: (String) -> Boolean): String {
    if (!exists(desired)) return desired
    val dot = desired.lastIndexOf('.')
    val (base, ext) = if (dot > 0) desired.substring(0, dot) to desired.substring(dot) else desired to ""
    var n = 1
    while (true) {
        val candidate = "$base ($n)$ext"
        if (!exists(candidate)) return candidate
        n++
    }
}

/**
 * Partial files are keyed by the sender's key fingerprint, name and size so a retried transfer of the
 * same file finds its earlier bytes, while a different file that happens to share the name does not.
 * The fingerprint rather than the claimed device id, so no other device can append to the partial.
 */
fun partialFileName(senderFingerprint: String, name: String, size: Long): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("${senderFingerprint.uppercase()}/$name/$size".toByteArray())
    val hex = digest.take(10).joinToString("") { "%02x".format(it) }
    return "$PARTIAL_PREFIX$hex.part"
}

private const val PARTIAL_PREFIX = ".voidarray-"

/**
 * Cleans a peer-chosen device name for display: no control or direction-override characters, no line
 * breaks, and short enough that it cannot push warnings off the screen. Empty becomes a neutral label.
 */
fun sanitizeAlias(raw: String): String {
    // Whitespace becomes spaces first, since tabs and line breaks are control characters that would vanish.
    val cleaned = stripInvisible(raw.replace(Regex("\\s+"), " ")).replace(Regex(" +"), " ").trim()
    return cleaned.take(MAX_ALIAS_LENGTH).trim().ifEmpty { "Unnamed device" }
}

const val MAX_ALIAS_LENGTH = 64

/**
 * Removes control characters and invisible formatting characters. The latter include right-to-left
 * overrides, which can make "photo‮gnp.exe" display as "photoexe.png", and zero-width characters
 * that make two different names look identical.
 */
private fun stripInvisible(text: String): String = text.filterNot { c ->
    when (Character.getType(c).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }
}
