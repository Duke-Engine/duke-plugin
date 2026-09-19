package uz.duke.plugin.duke

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.util.PsiTreeUtil
import uz.duke.plugin.DukeBundle
import javax.swing.Icon

object DukeLanguage : Language("Duke")

/** `.duke`: the engine's data files, read by `uz.duke.core.data.DukeText`. */
object DukeFileType : LanguageFileType(DukeLanguage) {
    override fun getName() = "Duke"
    override fun getDescription() = DukeBundle.message("duke.filetype.description")
    override fun getDefaultExtension() = "duke"
    override fun getIcon(): Icon = AllIcons.FileTypes.Config
}

class DukeFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, DukeLanguage) {
    override fun getFileType() = DukeFileType

    val blocks: List<DukeBlock>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeBlock::class.java)

    override fun toString() = "Duke file"
}
