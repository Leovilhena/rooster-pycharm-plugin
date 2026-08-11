package dev.turbofieldfare.plugin.edit

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.turbofieldfare.plugin.tools.EditPreview
import dev.turbofieldfare.plugin.tools.ProjectFiles

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
 * when the gate allowed the edit. It re-checks project confinement anyway: this
 * is the last code before bytes hit the disk, and a check here costs nothing.
 */
object FileEditApplier {

    fun apply(project: Project, preview: EditPreview): ApplyResult {
        val path = ProjectFiles.resolve(project, preview.relativePath)
            ?: return ApplyResult.Failed("\"${preview.relativePath}\" is outside the open project.")

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
