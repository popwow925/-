package cn.eririlily.launcher

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows the launcher's files (.minecraft, mods, saves, screenshots…) in the
 * system Files app, so players can add mods or back up worlds without root.
 * Document ids are "root" + the path below the data folder.
 */
class FilesProvider : DocumentsProvider() {
    private val base: File get() = File(context!!.filesDir, "EririLily").apply { mkdirs() }

    override fun onCreate() = true

    private fun fileOf(id: String): File {
        if (id == ROOT) return base
        if (!id.startsWith("$ROOT/")) throw FileNotFoundException(id)
        val f = File(base, id.removePrefix("$ROOT/")).canonicalFile
        if (!f.path.startsWith(base.canonicalPath)) throw FileNotFoundException(id)
        return f
    }

    private fun idOf(f: File): String {
        val rel = f.canonicalPath.removePrefix(base.canonicalPath).trimStart('/')
        return if (rel.isEmpty()) ROOT else "$ROOT/$rel"
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLS)
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT)
            add(Root.COLUMN_DOCUMENT_ID, ROOT)
            add(Root.COLUMN_TITLE, context!!.getString(R.string.files_root))
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_AVAILABLE_BYTES, base.freeSpace)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLS)
        row(c, fileOf(documentId))
        return c
    }

    override fun queryChildDocuments(parentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLS)
        fileOf(parentId).listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { row(c, it) }
        return c
    }

    private fun row(c: MatrixCursor, f: File) {
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE
        if (f.isDirectory) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        val name = if (f == base) context!!.getString(R.string.files_root) else f.name
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idOf(f))
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(f))
            add(Document.COLUMN_SIZE, if (f.isDirectory) null else f.length())
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeOf(f: File): String {
        if (f.isDirectory) return Document.MIME_TYPE_DIR
        val ext = f.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentId: String, mimeType: String, displayName: String): String {
        val parent = fileOf(parentId)
        var f = File(parent, displayName.replace('/', '_'))
        var n = 1
        while (f.exists()) f = File(parent, "${f.nameWithoutExtension} (${n++})${if (f.extension.isEmpty()) "" else "." + f.extension}")
        if (mimeType == Document.MIME_TYPE_DIR) f.mkdirs() else f.createNewFile()
        return idOf(f)
    }

    override fun deleteDocument(documentId: String) {
        val f = fileOf(documentId)
        if (f == base) throw SecurityException("root")
        f.deleteRecursively()
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = fileOf(documentId)
        val t = File(f.parentFile, displayName.replace('/', '_'))
        if (!f.renameTo(t)) throw FileNotFoundException(displayName)
        return idOf(t)
    }

    override fun isChildDocument(parentId: String, documentId: String): Boolean =
        documentId == parentId || documentId.startsWith("$parentId/")

    companion object {
        const val AUTHORITY = "cn.eririlily.launcher.files"
        const val ROOT = "root"
        private val ROOT_COLS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES)
        private val DOC_COLS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}
