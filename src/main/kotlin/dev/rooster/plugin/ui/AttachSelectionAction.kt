package dev.rooster.plugin.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.rooster.plugin.chat.Attachment
import dev.rooster.plugin.chat.RoosterAttachments
import dev.rooster.plugin.tools.ProjectFiles

/**
 * "Attach to Rooster Chat" in the editor's right-click menu.
 *
 * Right-click only, deliberately: it is the one direct gesture for "this bit,
 * here", and a toolbar button would need the same selection logic on a second
 * surface with no evidence anyone reaches for it first.
 *
 * With no selection the whole file is attached, which is what Copilot Chat does
 * and what "attach this file" means when the caret is just sitting somewhere.
 *
 * Content is taken from the editor's `Document`, not from disk: the document is
 * what the user is looking at, so attaching after an unsaved edit attaches what
 * they see rather than a stale copy.
 */
class AttachSelectionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null &&
                e.getData(CommonDataKeys.EDITOR) != null &&
                e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val document = editor.document
        val selection = editor.selectionModel

        // Reuses the existing project-relative display helper rather than
        // inventing a second path-relativising rule.
        val path = runCatching { file.toNioPath() }.getOrNull()
        val shown = path?.let { ProjectFiles.display(project, it) } ?: file.name

        val attachment = if (selection.hasSelection()) {
            val start = document.getLineNumber(selection.selectionStart) + 1
            // -1 so a selection ending exactly at a line start does not claim the
            // following line, which the user did not select any of.
            val lastOffset = maxOf(selection.selectionStart, selection.selectionEnd - 1)
            val end = document.getLineNumber(lastOffset) + 1
            Attachment(shown, start, end, selection.selectedText.orEmpty())
        } else {
            Attachment(shown, null, null, document.text)
        }

        RoosterAttachments.getInstance(project).add(attachment)
    }
}
