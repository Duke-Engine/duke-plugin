package uz.duke.plugin.ini

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import uz.duke.plugin.DukeBundle
import javax.swing.Icon

object DukeIniLanguage : Language("DukeIni")

// ponytail: claims every *.ini, fine for an IDE opened on duke-engine; sniff the content first
// if the plugin ever shares an IDE with ordinary INI files.
object DukeIniFileType : LanguageFileType(DukeIniLanguage) {
    override fun getName() = "Duke INI"
    override fun getDescription() = DukeBundle.message("filetype.description")
    override fun getDefaultExtension() = "ini"
    override fun getIcon(): Icon = AllIcons.FileTypes.Config
}

class DukeIniFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, DukeIniLanguage) {
    override fun getFileType() = DukeIniFileType

    val blocks: List<DukeIniBlock>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, DukeIniBlock::class.java)

    /** Blocks this file declares, by name: `Object Rogue` declares `Rogue`. */
    val declarations: Map<String, List<DukeIniBlock>>
        get() = CachedValuesManager.getCachedValue(this) {
            CachedValueProvider.Result.create(blocks.filter { it.name != null }.groupBy { it.name!! }, this)
        }

    /** Section type -> key -> how many sections of that type write the key more than once. */
    val repeatedKeys: Map<String, Map<String, Int>>
        get() = CachedValuesManager.getCachedValue(this) {
            val counts = HashMap<String, HashMap<String, Int>>()
            for (section in PsiTreeUtil.findChildrenOfType(this, DukeIniSection::class.java)) {
                val perType = counts.getOrPut(section.sectionType) { HashMap() }
                section.fields.groupingBy { it.key }.eachCount()
                    .filterValues { it > 1 }
                    .keys.forEach { perType.merge(it, 1, Int::plus) }
            }
            CachedValueProvider.Result.create(counts, this)
        }

    override fun toString() = "Duke INI file"
}
