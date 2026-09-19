package uz.duke.plugin.inspector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import uz.duke.plugin.assets.AssetKind
import uz.duke.plugin.duke.DukeBlock
import uz.duke.plugin.duke.DukeFile
import uz.duke.plugin.duke.DukeLinks
import javax.swing.JComponent

/**
 * A sound for a moment of a block's: `died.Skeleton`, heard when the Skeleton dies. The client raises a moment by
 * the name of what it happened to and plays the sound so named, so the moments offered are the ones the game's
 * sounds are named for already — `died` of `died.Boss` — where what follows the dot reads as a template's name
 * does, rather than a key (`skill.Q`) or a word (`vo.kill`). The new sound is a copy of the last one for its moment,
 * written after it: heard at once, and never a sound with no files for the game to pick from.
 */
internal object NewSound {

    fun start(project: Project, anchor: JComponent, block: DukeBlock?) {
        val name = block?.field("Name")?.valueText
        val moments = block?.let(::momentsFor).orEmpty()
        if (name == null || moments.isEmpty()) {
            JBPopupFactory.getInstance().createMessage("No sound in the project is named for a moment this block has none of.").showUnderneathOf(anchor)
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(moments.keys.toList())
            .setTitle("A Sound for $name, When")
            .setRenderer(SimpleListCellRenderer.create("") { "$it.$name" })
            .setItemChosenCallback { moment -> ApplicationManager.getApplication().invokeLater { create(project, "$moment.$name", moments.getValue(moment)) } }
            .createPopup().showUnderneathOf(anchor)
    }

    /** Each moment [block] has no sound of its own for, with the last sound there is for it. */
    fun momentsFor(block: DukeBlock): Map<String, DukeBlock> {
        val name = block.field("Name")?.valueText ?: return emptyMap()
        val sounds = DukeLinks.namedBlocks(block)
            .filter { (_, sound) -> sound.fields.any { field -> field.values.any { AssetKind.of(it.unquoted) == AssetKind.AUDIO } } }
            .sortedWith(compareBy({ it.second.containingFile.name }, { it.second.textRange.startOffset }))
        val own = sounds.map { it.first }.toSet()
        val moments = sortedMapOf<String, DukeBlock>()
        for ((sound, it) in sounds) {
            val moment = sound.substringBeforeLast('.', "")
            val of = sound.substringAfterLast('.')
            if (moment.isEmpty() || of.length < 2 || !of[0].isUpperCase() || "$moment.$name" in own) continue
            moments[moment] = it
        }
        return moments
    }

    /** [like]'s text with [name] for its own: every other line — its channel, its gain, its files — kept. */
    fun textFor(like: DukeBlock, name: String): String =
        like.text.replaceFirst(Regex("(?m)^(\\s*Name\\s*=\\s*).*$"), "$1" + Regex.escapeReplacement(name))

    private fun create(project: Project, name: String, like: DukeBlock) {
        if (!like.isValid) return
        val file = like.containingFile as? DukeFile ?: return
        val text = textFor(like, name)
        var at = -1
        DukeEdits.write(project, file, "New Sound $name") { document ->
            at = document.getLineEndOffset(document.getLineNumber(like.textRange.endOffset))
            document.insertString(at, "\n\n$text")
        }
        val virtual = file.virtualFile ?: return
        if (at >= 0) OpenFileDescriptor(project, virtual, at + 2).navigate(true)
    }
}
