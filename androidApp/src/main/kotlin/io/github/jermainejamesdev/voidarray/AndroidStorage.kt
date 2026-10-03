package io.github.jermainejamesdev.voidarray

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import io.github.jermainejamesdev.voidarray.engine.DestinationFolder
import io.github.jermainejamesdev.voidarray.engine.FileHandle
import io.github.jermainejamesdev.voidarray.engine.KeyValueStore
import io.github.jermainejamesdev.voidarray.engine.skipFully
import io.github.jermainejamesdev.voidarray.engine.uniqueName
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** A file picked through SAF or received from the share sheet. */
class ContentUriFileHandle private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
) : FileHandle {

    override fun open(offset: Long): InputStream {
        val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        if (offset > 0) {
            try {
                stream.skipFully(offset)
            } catch (e: IOException) {
                stream.close()
                throw e
            }
        }
        return stream
    }

    companion object {
        /** Returns null when the provider cannot report an exact size, which the protocol requires up front. */
        fun from(resolver: ContentResolver, uri: Uri): ContentUriFileHandle? {
            var name: String? = null
            var size: Long? = null
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex >= 0) name = cursor.getString(nameIndex)
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        }
                    }
            }
            if (size == null) {
                size = runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0 } }
                }.getOrNull()
            }
            val exactSize = size ?: return null
            return ContentUriFileHandle(resolver, uri, name ?: uri.lastPathSegment ?: "file", exactSize)
        }
    }
}

/**
 * A folder the user granted through ACTION_OPEN_DOCUMENT_TREE. Uses DocumentsContract directly rather
 * than DocumentFile, which issues a separate ContentResolver query for every attribute of every file.
 */
class SafTreeDestination(context: Context, val treeUri: Uri) : DestinationFolder {
    private val resolver = context.contentResolver
    private val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val folderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
    private val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)

    // Providers may adjust a requested name on create, so partials are tracked by the URI they returned.
    private val partialUris = ConcurrentHashMap<String, Uri>()

    override val label: String = treeDocumentId.let { id ->
        if (id.startsWith("primary:")) "Internal storage/" + id.removePrefix("primary:") else id
    }

    private class Child(val uri: Uri, val size: Long)

    private fun children(): Map<String, Child> {
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE)
        val result = HashMap<String, Child>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val size = if (cursor.isNull(2)) 0L else cursor.getLong(2)
                result[name] = Child(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId), size)
            }
        }
        return result
    }

    override fun existingSizes(names: Collection<String>): Map<String, Long> {
        val wanted = names.toSet()
        return children().filterKeys { it in wanted }.mapValues { it.value.size }
    }

    override fun openPartial(partialName: String, offset: Long): OutputStream {
        val existing = children()[partialName]
        if (offset == 0L) {
            // Some providers ignore the truncate flag on "w", so a fresh start replaces the document instead.
            existing?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
            val uri = DocumentsContract.createDocument(resolver, folderUri, "application/octet-stream", partialName)
                ?: throw IOException("Could not create a file in $label")
            partialUris[partialName] = uri
            return resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open $uri")
        }
        val child = existing ?: throw IOException("Partial file for resume is missing")
        if (child.size != offset) throw IOException("Partial file is ${child.size} bytes, expected $offset")
        partialUris[partialName] = child.uri
        return openForAppend(child.uri, offset)
    }

    private fun openForAppend(uri: Uri, offset: Long): OutputStream {
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
        if (descriptor != null) {
            val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            stream.channel.position(offset)
            return stream
        }
        return resolver.openOutputStream(uri, "wa") ?: throw IOException("Could not open $uri for append")
    }

    override fun commit(partialName: String, desiredName: String): String {
        val current = children()
        val uri = partialUris.remove(partialName) ?: current[partialName]?.uri
            ?: throw IOException("Finished file disappeared before it could be renamed")
        val finalName = uniqueName(desiredName) { it in current }
        val renamed = DocumentsContract.renameDocument(resolver, uri, finalName)
            ?: throw IOException("Could not rename to $finalName")
        return displayName(renamed) ?: finalName
    }

    override fun discard(partialName: String) {
        val uri = partialUris.remove(partialName) ?: children()[partialName]?.uri ?: return
        DocumentsContract.deleteDocument(resolver, uri)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}

class SharedPreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String?) {
        preferences.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
