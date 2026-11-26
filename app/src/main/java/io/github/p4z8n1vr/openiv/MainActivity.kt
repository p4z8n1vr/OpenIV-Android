package io.github.p4z8n1vr.openiv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import io.github.p4z8n1vr.openiv.archive.RpfArchive
import io.github.p4z8n1vr.openiv.archive.RpfDirectory
import io.github.p4z8n1vr.openiv.archive.RpfEncryption
import io.github.p4z8n1vr.openiv.archive.RpfEntry
import io.github.p4z8n1vr.openiv.archive.RpfException
import io.github.p4z8n1vr.openiv.archive.RpfFile
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private class ArchiveView(
        val archive: RpfArchive,
        val name: String,
        val parentEntry: RpfFile? = null,
    ) {
        val directories = mutableListOf(archive.root)
        val directory get() = directories.last()
        var revision = 0
        var savedRevision = 0
        val hasUnsavedCopy get() = revision != savedRevision
    }

    private class DocumentRequest(
        val action: (Uri) -> Unit,
        val cancel: () -> Unit,
    )

    private class ResourceIOException(val messageResource: Int) : IOException()

    private val worker = Executors.newSingleThreadExecutor()
    private val archives = mutableListOf<ArchiveView>()
    private val current get() = archives.lastOrNull()
    private lateinit var session: File
    private lateinit var accessDiagnostics: AccessDiagnostics
    private lateinit var main: FrameLayout
    private lateinit var body: FrameLayout
    private var progress: View? = null
    private var pendingDocument: DocumentRequest? = null
    private var aesKey: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = File(cacheDir, "openiv-${UUID.randomUUID()}")
        accessDiagnostics = AccessDiagnostics(this)
        setContentView(R.layout.activity_main)
        main = findViewById(R.id.main_root)
        body = findViewById(R.id.screen_content)
        main.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        window.setDecorFitsSystemWindows(false)
        main.requestApplyInsets()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ::goBack,
        )
        render()
    }

    override fun onDestroy() {
        accessDiagnostics.close()
        aesKey?.fill(0)
        worker.execute { session.deleteRecursively() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun render() {
        body.removeAllViews()
        val view = current
        if (view == null) {
            renderHome()
            return
        }

        layoutInflater.inflate(R.layout.content_archive, body, true)
        body.findViewById<Button>(R.id.archive_back).setOnClickListener { goBack() }
        body.findViewById<Button>(R.id.archive_actions).setOnClickListener { showArchiveActions(view) }
        body.findViewById<TextView>(R.id.archive_title).text = view.name
        val path = view.directories.drop(1).joinToString(getString(R.string.archive_path_separator)) { it.name }
        body.findViewById<TextView>(R.id.archive_path).text = path.ifEmpty { getString(R.string.root_folder) }
        if (view.hasUnsavedCopy || (view.parentEntry != null && view.archive.hasChanges)) {
            body.findViewById<TextView>(R.id.unsaved_status).visibility = View.VISIBLE
        }

        val search = body.findViewById<EditText>(R.id.archive_search)
        val list = body.findViewById<ListView>(R.id.archive_entries)
        val empty = body.findViewById<TextView>(R.id.empty_entries)
        list.emptyView = empty

        val entries = view.directory.children.sortedWith(
            compareBy<RpfEntry> { it !is RpfDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        fun applyFilter(query: String) {
            val matches = entries.filter { it.name.contains(query.trim(), ignoreCase = true) }
            empty.setText(if (query.isBlank()) R.string.empty_folder else R.string.no_results)
            list.adapter = EntryAdapter(matches)
        }
        applyFilter("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s.toString())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { parent, _, position, _ ->
            when (val entry = parent.getItemAtPosition(position) as RpfEntry) {
                is RpfDirectory -> {
                    view.directories.add(entry)
                    render()
                }
                is RpfFile -> showEntryActions(view, entry)
            }
        }
        list.setOnItemLongClickListener { parent, _, position, _ ->
            showEntryActions(view, parent.getItemAtPosition(position) as RpfEntry)
            true
        }
        body.requestFocus()
    }

    private fun renderHome() {
        layoutInflater.inflate(R.layout.content_home, body, true)
        body.findViewById<Button>(R.id.open_archive).setOnClickListener { chooseArchive() }
        body.findViewById<Button>(R.id.key_options).apply {
            setText(if (aesKey == null) R.string.load_key else R.string.key_loaded)
            setOnClickListener { showKeyOptions() }
        }
        body.findViewById<Button>(R.id.access_status).setOnClickListener { showAccessStatus() }
    }

    private fun showAccessStatus() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.access_diagnostics)
            .setView(R.layout.dialog_access_status)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        val root = dialog.findViewById<TextView>(R.id.root_status)
        val shizuku = dialog.findViewById<TextView>(R.id.shizuku_status)
        accessDiagnostics.check { status ->
            if (dialog.isShowing) {
                root.setText(status.root.messageResId)
                shizuku.setText(status.shizuku.messageResId)
            }
        }
    }

    private inner class EntryAdapter(entries: List<RpfEntry>) :
        ArrayAdapter<RpfEntry>(this@MainActivity, R.layout.row_archive_entry, entries) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = checkNotNull(getItem(position))
            val card = convertView ?: layoutInflater.inflate(R.layout.row_archive_entry, parent, false)
            val separator = getString(R.string.entry_detail_separator)
            val detail = when (item) {
                is RpfDirectory -> resources.getQuantityString(
                    R.plurals.folder_entries,
                    item.children.size,
                    item.children.size,
                )
                is RpfFile -> Formatter.formatFileSize(this@MainActivity, item.size)
            }
            card.findViewById<TextView>(R.id.archive_entry_name).text = item.name
            card.findViewById<TextView>(R.id.archive_entry_detail).text = when (item) {
                is RpfDirectory -> getString(R.string.folder) + separator + detail
                is RpfFile -> buildList {
                    if (item.isResource) add(getString(R.string.resource))
                    if (item.isCompressed) add(getString(R.string.compressed))
                    if (item.isEntryEncrypted) add(getString(R.string.encrypted))
                    add(detail)
                }.joinToString(separator)
            }
            return card
        }
    }

    private fun chooseArchive() {
        requestDocument(Intent(Intent.ACTION_OPEN_DOCUMENT).forFile("*/*")) { uri ->
            runJob(R.string.opening_archive, {
                val source = cacheDocument(uri)
                try {
                    ArchiveView(RpfArchive.open(source, aesKey), documentName(uri, R.string.archive_fallback))
                } catch (failure: Exception) {
                    source.delete()
                    throw failure
                }
            }) {
                archives.add(it)
                render()
            }
        }
    }

    private fun showKeyOptions() {
        if (aesKey == null) {
            loadKey()
        } else {
            menu(R.string.key_loaded, listOf(
                R.string.change_key to ::loadKey,
                R.string.forget_key to {
                    aesKey?.fill(0)
                    aesKey = null
                    render()
                },
            ))
        }
    }

    private fun loadKey() {
        requestDocument(Intent(Intent.ACTION_OPEN_DOCUMENT).forFile("*/*")) { uri ->
            runJob(R.string.opening_entry, {
                val input = contentResolver.openInputStream(uri)
                    ?: throw ResourceIOException(R.string.error_document_read)
                val bytes = input.use { it.readNBytes(33) }
                if (bytes.size != 32) {
                    bytes.fill(0)
                    throw ResourceIOException(R.string.error_aes_key)
                }
                bytes
            }) {
                aesKey?.fill(0)
                aesKey = it
                render()
            }
        }
    }

    private fun showArchiveActions(view: ArchiveView) {
        val handlers = mapOf<Int, () -> Unit>(
            R.string.archive_info to { showArchiveInfo(view) },
            R.string.add_file to { addFile(view, view.directory) },
            R.string.new_text to { editText(view, null) },
            R.string.extract_folder to { extractFolder(view, view.directory) },
            R.string.save_as to { saveArchive(view) },
        )
        val labels = resources.obtainTypedArray(R.array.archive_action_labels)
        val actions = try {
            MutableList(labels.length()) { index ->
                val label = labels.getResourceId(index, 0)
                label to handlers.getValue(label)
            }
        } finally {
            labels.recycle()
        }
        if (view.parentEntry != null && view.archive.hasChanges) {
            actions.add(R.string.apply_to_parent to { applyNestedChanges(view) })
        }
        menu(R.string.archive_actions, actions)
    }

    private fun showArchiveInfo(view: ArchiveView) {
        val encryption = view.archive.encryptionMode
        val displayEncryption = if (encryption == RpfEncryption.NONE) {
            getString(R.string.encryption_unencrypted)
        } else {
            encryption.displayName
        }
        val details = listOf(
            getString(R.string.archive_format),
            getString(R.string.archive_encryption, displayEncryption),
            getString(R.string.archive_entries, view.archive.entryCount),
            getString(R.string.archive_depth, archives.size),
        ).joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle(view.name)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showEntryActions(view: ArchiveView, entry: RpfEntry) {
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (entry is RpfFile) {
            if (!entry.isResource && entry.name.endsWith(".rpf", ignoreCase = true)) {
                actions.add(R.string.nested_archive to { openNested(view, entry) })
            } else if (!entry.isResource) {
                actions.add(R.string.view_edit_text to { loadText(view, entry) })
            }
            actions.add(R.string.extract to { extractFile(view, entry) })
            if (!entry.isResource || !entry.isEntryEncrypted) {
                actions.add(R.string.replace to { replaceFile(view, entry) })
            }
        } else if (entry is RpfDirectory) {
            actions.add(R.string.extract_folder to { extractFolder(view, entry) })
        }
        actions.add(R.string.rename to { renameEntry(view, entry) })
        actions.add(R.string.delete to { deleteEntry(view, entry) })
        menu(entry.name, actions)
    }

    private fun openNested(view: ArchiveView, entry: RpfFile) {
        runJob(R.string.opening_archive, {
            val source = temporaryFile()
            try {
                source.outputStream().use { view.archive.extract(entry, it) }
                ArchiveView(RpfArchive.open(source, aesKey), entry.name, entry)
            } catch (failure: Exception) {
                source.delete()
                throw failure
            }
        }) {
            archives.add(it)
            render()
        }
    }

    private fun addFile(view: ArchiveView, directory: RpfDirectory) {
        requestDocument(Intent(Intent.ACTION_OPEN_DOCUMENT).forFile("*/*")) { uri ->
            runJob(R.string.updating_archive, {
                val name = documentName(uri, R.string.file_fallback)
                withNewSource(cacheDocument(uri)) { source -> view.archive.add(directory, name, source) }
            }) { changed(view, R.string.entry_added) }
        }
    }

    private fun replaceFile(view: ArchiveView, entry: RpfFile) {
        requestDocument(Intent(Intent.ACTION_OPEN_DOCUMENT).forFile("*/*")) { uri ->
            runJob(R.string.updating_archive, {
                withNewSource(cacheDocument(uri)) { source -> view.archive.replace(entry, source) }
            }) { changed(view, R.string.entry_updated) }
        }
    }

    private fun renameEntry(view: ArchiveView, entry: RpfEntry) {
        editDialog(entry.name, R.layout.dialog_rename, R.string.rename) { dialog ->
            val input = dialog.findViewById<EditText>(R.id.entry_name_input)
            input.setText(entry.name)
            input.selectAll()
            val onSave: () -> Unit = save@{
                val name = input.text.toString()
                if (name == entry.name) return@save
                runJob(R.string.updating_archive, { view.archive.rename(entry, name) }) {
                    changed(view, R.string.entry_updated)
                }
            }
            onSave
        }
    }

    private fun deleteEntry(view: ArchiveView, entry: RpfEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_prompt, entry.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runJob(R.string.updating_archive, { view.archive.remove(entry) }) {
                    changed(view, R.string.entry_deleted)
                }
            }
            .show()
    }

    private fun loadText(view: ArchiveView, entry: RpfFile) {
        runJob(R.string.opening_entry, {
            if (entry.size > TEXT_LIMIT) throw ResourceIOException(R.string.error_text_size)
            val bytes = java.io.ByteArrayOutputStream()
            view.archive.extract(entry, bytes)
            val decoded = try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (_: CharacterCodingException) {
                throw ResourceIOException(R.string.error_text_encoding)
            }
            if (decoded.any { it.isISOControl() && it !in "\t\n\r" }) {
                throw ResourceIOException(R.string.error_text_encoding)
            }
            decoded
        }) { editText(view, entry, it) }
    }

    private fun editText(view: ArchiveView, entry: RpfFile?, initial: String = "") {
        val title = entry?.name ?: getString(R.string.new_text)
        val directory = view.directory
        editDialog(title, R.layout.dialog_edit_text, R.string.save_changes) { dialog ->
            val name = dialog.findViewById<EditText>(R.id.entry_name_input)
            val text = dialog.findViewById<EditText>(R.id.entry_text_input)
            if (entry != null) name.visibility = View.GONE
            text.setText(initial)
            val onSave: () -> Unit = save@{
                val updated = text.text.toString()
                val bytes = updated.toByteArray(Charsets.UTF_8)
                if (bytes.size > TEXT_LIMIT) throw ResourceIOException(R.string.error_text_size)
                if (entry != null && initial == updated) return@save
                val fileName = name.text.toString()
                runJob(R.string.updating_archive, {
                    withNewSource(temporaryFile()) { source ->
                        source.writeBytes(bytes)
                        if (entry == null) view.archive.add(directory, fileName, source)
                        else view.archive.replace(entry, source)
                    }
                }) { changed(view, if (entry == null) R.string.entry_added else R.string.entry_updated) }
            }
            onSave
        }
    }

    private fun applyNestedChanges(view: ArchiveView) {
        val parent = archives.getOrNull(archives.lastIndex - 1) ?: return
        val entry = view.parentEntry ?: return
        runJob(R.string.saving_archive, {
            withNewSource(temporaryFile()) { source ->
                source.outputStream().use(view.archive::write)
                parent.archive.replace(entry, source)
            }
        }) {
            archives.removeAt(archives.lastIndex)
            changed(parent, R.string.nested_applied)
        }
    }

    private fun extractFile(view: ArchiveView, entry: RpfFile) {
        val name = entry.name.takeIf(::isDocumentName) ?: getString(R.string.file_fallback)
        requestDocument(Intent(Intent.ACTION_CREATE_DOCUMENT).forFile("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, name)) { uri ->
            runJob(R.string.extracting, {
                writeDocument(uri, view.archive, entry)
            }) { notify(R.string.extracted) }
        }
    }

    private fun extractFolder(view: ArchiveView, directory: RpfDirectory) {
        requestDocument(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) { uri ->
            runJob(R.string.extracting, {
                val providerRoot = DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    DocumentsContract.getTreeDocumentId(uri),
                )
                val folderName = if (directory == view.archive.root) {
                    view.name.substringBeforeLast('.').takeIf(::isDocumentName) ?: getString(R.string.folder_fallback)
                } else {
                    checkedDocumentName(directory.name)
                }
                val destination = createDocument(providerRoot, DocumentsContract.Document.MIME_TYPE_DIR, folderName)
                try {
                    val queue = ArrayDeque<Pair<RpfDirectory, Uri>>()
                    queue.addLast(directory to destination)
                    while (queue.isNotEmpty()) {
                        val (sourceDirectory, outputDirectory) = queue.removeFirst()
                        for (entry in sourceDirectory.children) {
                            val name = checkedDocumentName(entry.name)
                            when (entry) {
                                is RpfDirectory -> queue.addLast(
                                    entry to createDocument(outputDirectory, DocumentsContract.Document.MIME_TYPE_DIR, name),
                                )
                                is RpfFile -> writeDocument(
                                    createDocument(outputDirectory, "application/octet-stream", name),
                                    view.archive,
                                    entry,
                                )
                            }
                        }
                    }
                } catch (failure: Exception) {
                    removeDocument(destination)
                    throw failure
                }
            }) { notify(R.string.extracted) }
        }
    }

    private fun saveArchive(view: ArchiveView) {
        runJob(R.string.saving_archive, {
            val copy = temporaryFile()
            try {
                copy.outputStream().use(view.archive::write)
                copy
            } catch (failure: Exception) {
                copy.delete()
                throw failure
            }
        }) { copy ->
            val name = view.name.takeIf(::isDocumentName) ?: getString(R.string.archive_fallback)
            requestDocument(
                Intent(Intent.ACTION_CREATE_DOCUMENT).forFile("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, name),
                onCancel = { copy.delete() },
            ) { uri ->
                runJob(R.string.saving_archive, {
                    try {
                        writeNewDocument(uri) { output -> copy.inputStream().use { it.copyTo(output) } }
                    } finally {
                        copy.delete()
                    }
                }) {
                    view.savedRevision = view.revision
                    render()
                    notify(R.string.saved)
                }
            }
        }
    }

    private fun goBack() {
        if (progress != null || pendingDocument != null) return
        val view = current
        when {
            view == null -> finish()
            view.directories.size > 1 -> {
                view.directories.removeAt(view.directories.lastIndex)
                render()
            }
            view.parentEntry != null && view.archive.hasChanges -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.unsaved)
                    .setMessage(getString(R.string.leave_nested_prompt, view.name))
                    .setPositiveButton(R.string.apply_to_parent) { _, _ -> applyNestedChanges(view) }
                    .setNeutralButton(R.string.discard_changes) { _, _ -> closeArchive() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            view.hasUnsavedCopy -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.unsaved)
                    .setMessage(getString(R.string.leave_prompt, view.name))
                    .setNegativeButton(R.string.keep_editing, null)
                    .setNeutralButton(R.string.discard_changes) { _, _ -> closeArchive() }
                    .setPositiveButton(R.string.save_as) { _, _ -> saveArchive(view) }
                    .show()
            }
            else -> closeArchive()
        }
    }

    private fun closeArchive() {
        archives.removeAt(archives.lastIndex)
        if (archives.isEmpty()) worker.execute { session.deleteRecursively() }
        render()
    }

    private fun changed(view: ArchiveView, message: Int) {
        view.revision++
        render()
        notify(message)
    }

    private fun requestDocument(intent: Intent, onCancel: () -> Unit = {}, onResult: (Uri) -> Unit) {
        if (pendingDocument != null) return
        pendingDocument = DocumentRequest(onResult, onCancel)
        @Suppress("DEPRECATION")
        try {
            startActivityForResult(intent, DOCUMENT_REQUEST)
        } catch (failure: Exception) {
            pendingDocument = null
            onCancel()
            showError(failure)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DOCUMENT_REQUEST) return
        val request = pendingDocument ?: return
        pendingDocument = null
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) request.action(uri) else request.cancel()
    }

    private fun Intent.forFile(mimeType: String): Intent = addCategory(Intent.CATEGORY_OPENABLE).setType(mimeType)

    private fun documentName(uri: Uri, fallback: Int): String {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.takeIf { it.isNotBlank() }
            ?: getString(fallback)
    }

    private fun temporaryFile(): File {
        if (!session.isDirectory && !session.mkdirs()) throw ResourceIOException(R.string.error_document_write)
        return File.createTempFile("openiv-", ".tmp", session)
    }

    private fun cacheDocument(uri: Uri): File {
        val target = temporaryFile()
        return try {
            val input = contentResolver.openInputStream(uri) ?: throw ResourceIOException(R.string.error_document_read)
            input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
            target
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }

    private fun <T> withNewSource(source: File, action: (File) -> T): T {
        try {
            return action(source)
        } catch (failure: Exception) {
            source.delete()
            throw failure
        }
    }

    private fun writeDocument(uri: Uri, archive: RpfArchive, entry: RpfFile) {
        writeNewDocument(uri) { archive.extract(entry, it) }
    }

    private fun writeNewDocument(uri: Uri, action: (OutputStream) -> Unit) {
        try {
            val stream = contentResolver.openOutputStream(uri, "wt")
                ?: throw ResourceIOException(R.string.error_document_write)
            stream.use(action)
        } catch (failure: Exception) {
            removeDocument(uri)
            throw failure
        }
    }

    private fun removeDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
    }

    private fun createDocument(parent: Uri, mimeType: String, name: String): Uri =
        DocumentsContract.createDocument(contentResolver, parent, mimeType, name)
            ?: throw ResourceIOException(R.string.error_document_write)

    private fun checkedDocumentName(name: String): String =
        name.takeIf(::isDocumentName) ?: throw ResourceIOException(R.string.error_export_name)

    private fun isDocumentName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }

    private fun <T> runJob(label: Int, action: () -> T, done: (T) -> Unit) {
        if (progress != null) return
        val overlay = layoutInflater.inflate(R.layout.view_job_progress, main, false)
        overlay.findViewById<TextView>(R.id.job_progress_message).setText(label)
        main.addView(overlay)
        progress = overlay
        body.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        overlay.requestFocus()
        worker.execute {
            try {
                val result = action()
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        endJob(overlay)
                        done(result)
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        endJob(overlay)
                        showError(failure)
                    }
                }
            }
        }
    }

    private fun endJob(overlay: View) {
        main.removeView(overlay)
        body.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        progress = null
    }

    private fun showError(failure: Exception) {
        val message = when (failure) {
            is ResourceIOException -> failure.messageResource
            is RpfException -> failure.code.messageResource()
            else -> R.string.error_generic
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun notify(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun menu(title: Int, options: List<Pair<Int, () -> Unit>>) = menu(getString(title), options)

    private fun menu(title: String, options: List<Pair<Int, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.map { getString(it.first) }.toTypedArray()) { _, position -> options[position].second() }
            .show()
    }

    private fun editDialog(
        title: String,
        layout: Int,
        actionText: Int,
        bind: (AlertDialog) -> (() -> Unit),
    ) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(actionText, null)
            .create()
        dialog.setOnShowListener {
            val onSave = bind(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    onSave()
                    dialog.dismiss()
                } catch (failure: IOException) {
                    showError(failure)
                }
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    private companion object {
        const val DOCUMENT_REQUEST = 150
        const val TEXT_LIMIT = 1024 * 1024
    }
}
