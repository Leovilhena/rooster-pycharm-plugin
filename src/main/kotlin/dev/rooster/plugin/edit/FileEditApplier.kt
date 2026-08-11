package dev.rooster.plugin.edit

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.rooster.plugin.memory.GlobalMemory
import dev.rooster.plugin.tools.EditPreview
import dev.rooster.plugin.tools.PathScope
import dev.rooster.plugin.tools.ProjectFiles

/** Outcome of applying a proposed edit. */
sealed interface ApplyResult {
    data object Applied : ApplyResult
    data class Failed(val message: String) : ApplyResult
}

/**
 * Writes an approved [EditPreview] to disk.
 *
 * Goes through the **Document**, not `java.nio`, and inside a single
 * [WriteCommandAction]. That is what makes one Cmd+Z revert the whole edit, keeps
 * an already-open editor tab in sync instead of showing stale text, and lets the
 * IDE's own undo stack own the change. Writing the file directly would leave the
 * editor and the VFS disagreeing with the disk and give the user nothing to undo.
 *
 * This is called only from the Apply button on a proposal card, which exists only
 * when the gate allowed the edit. It re-checks confinement anyway: this is the
 * last code before bytes hit the disk, and a check here costs nothing.
 */
object FileEditApplier {

    fun apply(project: Project, preview: EditPreview): ApplyResult {
        val path = resolve(project, preview)
            ?: return ApplyResult.Failed(refusal(preview))

        return try {
            var failure: String? = null
            WriteCommandAction.writeCommandAction(project)
                .withName("TurboFieldfare: apply ${preview.relativePath}")
                // One group id for the whole command, so undo treats it as one step.
                .withGroupId(UNDO_GROUP)
                .run<RuntimeException> {
                    val file = findOrCreate(path.toString(), preview)
                    if (file == null) {
                        failure = "Could not create \"${preview.relativePath}\"."
                        return@run
                    }
                    val document = FileDocumentManager.getInstance().getDocument(file)
                    if (document == null) {
                        failure = "\"${preview.relativePath}\" is not a text file the IDE can edit."
                        return@run
                    }
                    document.setText(preview.newContent)
                    FileDocumentManager.getInstance().saveDocument(document)
                }
            failure?.let { ApplyResult.Failed(it) } ?: ApplyResult.Applied
        } catch (e: Exception) {
            ApplyResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Re-derives the absolute path, applying the confinement rule that belongs to
     * this preview's scope. Null means refuse.
     *
     * **Deliberately not a resolved path passed in by the caller.** The whole
     * value of this class being the last stop before the write is that it does
     * the check itself; accepting an already-resolved `Path` would make the
     * check the caller's problem, and the caller is UI code reacting to a button
     * on a card built from model output.
     *
     * Both branches check. `Project` goes through `ProjectFiles.resolve()`
     * exactly as before — byte-for-byte the same behaviour for `propose_edit`,
     * which never sets a scope. `GlobalMemory` cannot use that check at all,
     * since it points outside every project root, so it runs its own: the path
     * is rebuilt from a slug that must still validate, which is the only way a
     * name can become a file in that directory.
     */
    private fun resolve(project: Project, preview: EditPreview): java.nio.file.Path? =
        resolve(ProjectFiles.root(project), GlobalMemory.root(), preview)

    /**
     * Root-relative overload, so both confinement rules are unit-testable without
     * an IDE — the same trick `ProjectFiles.resolve` and `GlobalMemory.resolve`
     * already use. A check that cannot be tested is a check nobody notices losing.
     */
    internal fun resolve(
        projectRoot: java.nio.file.Path?,
        globalRoot: java.nio.file.Path,
        preview: EditPreview,
    ): java.nio.file.Path? = when (preview.scope) {
        PathScope.Project -> projectRoot?.let { ProjectFiles.resolve(it, preview.relativePath) }
        PathScope.GlobalMemory -> GlobalMemory.resolve(globalRoot, preview.relativePath)
    }

    private fun refusal(preview: EditPreview): String = when (preview.scope) {
        PathScope.Project -> "\"${preview.relativePath}\" is outside the open project."
        PathScope.GlobalMemory ->
            "\"${preview.relativePath}\" is not a valid memory topic name. Nothing was written."
    }

    private fun findOrCreate(absolutePath: String, preview: EditPreview): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        fs.refreshAndFindFileByPath(absolutePath)?.let { return it }
        if (!preview.isNewFile) return null

        val nioPath = java.nio.file.Path.of(absolutePath)
        val parent = VfsUtil.createDirectories(nioPath.parent.toString())
        return parent.createChildData(this, nioPath.fileName.toString())
    }

    private const val UNDO_GROUP = "TurboFieldfare.apply"
}
