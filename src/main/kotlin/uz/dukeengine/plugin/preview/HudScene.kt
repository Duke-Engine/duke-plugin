package uz.dukeengine.plugin.preview

import com.intellij.openapi.Disposable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.jcef.JBCefApp
import uz.dukeengine.plugin.assets.DukeAssets
import uz.dukeengine.plugin.duke.DukeBlock
import uz.dukeengine.plugin.duke.DukeLinks
import uz.dukeengine.plugin.duke.DukeRecords
import uz.dukeengine.plugin.inspector.Defaults
import uz.dukeengine.plugin.inspector.InspectorModel
import java.nio.file.Path
import javax.swing.JComponent

/** The hero's bar as the preview draws it: the resource root its pictures are read from, and the rest as JSON. */
class HudScene(val root: String, val json: String)

/**
 * The bar a block shapes, drawn over its form: the client's hero bar laid out by the game's `PanelLook` — which
 * blocks, in what order, at what sizes, in what colours — with every skin on its edges (a block with a name, a
 * texture, an inset, a scale and a tint, as the game's `Skin` is) and the words and pictures of its HUD. Drawn for
 * the look itself and for each skin, the skin being edited pointed at wherever it goes.
 */
object HudScenes {
    private const val LOOK = "uz.dukeengine.client3d.PanelLook"

    /** The scene for [model]'s block, or null where it is neither the bar's look nor one of its skins. Under a read action. */
    fun of(model: InspectorModel): HudScene? {
        val block = model.block?.element ?: return null
        val record = DukeRecords.recordOf(block) ?: return null
        val focus = when {
            record.qualifiedName == LOOK -> "bar"
            isSkin(record) -> block.field("Name")?.valueText ?: return null
            else -> return null
        }
        val root = DukeAssets.rootOf(block)?.path ?: return null
        val blocks = DukeLinks.everyBlock(block)
        // A game that writes no look of its own is drawn with the client's.
        val look = blocks.firstOrNull { DukeRecords.recordOf(it)?.qualifiedName == LOOK }?.let(::valuesOf)
            ?: JavaPsiFacade.getInstance(block.project).findClass(LOOK, GlobalSearchScope.allScope(block.project))?.let(::defaultsOf)
            ?: return null
        val scene = mapOf(
            "focus" to focus,
            "look" to look,
            "skins" to blocks.filter { DukeRecords.recordOf(it)?.let(::isSkin) == true }.map(::valuesOf),
            "hud" to blocks.firstOrNull { DukeRecords.recordOf(it)?.let { hud -> has(hud, "skillsWord", "itemsWord") } == true }?.let(::valuesOf),
        )
        return HudScene(root, Json.of(scene))
    }

    private fun isSkin(record: PsiClass) = has(record, "name", "texture", "inset", "scale", "tint")

    private fun has(record: PsiClass, vararg components: String) =
        components.all { name -> record.recordComponents.any { it.name == name } }

    /** Each component of [block]'s record as the file writes it, else as its record defaults it: text, or a list of it. */
    private fun valuesOf(block: DukeBlock): Map<String, Any?> {
        val record = DukeRecords.recordOf(block) ?: return emptyMap()
        val defaults = defaultsOf(record)
        return record.recordComponents.associate { component ->
            val field = block.field(DukeRecords.capitalized(component.name))
            component.name to when {
                field == null -> defaults[component.name]
                field.list != null -> field.values.map { it.unquoted }
                else -> field.valueText
            }
        }
    }

    private fun defaultsOf(record: PsiClass): Map<String, Any?> = Defaults.of(record).mapValues { (_, text) ->
        when {
            text == null || text == "none" -> null
            text.startsWith("[") && text.endsWith("]") -> text.removeSurrounding("[", "]").split(',').map(String::trim).filter(String::isNotEmpty)
            else -> text
        }
    }
}

/** A page of the IDE's own browser that draws a [HudScene]. */
class HudPreview private constructor(parent: Disposable) {
    private val page = DukeBrowser(parent, "viewer/hud.html") { _, _ -> }

    val component: JComponent get() = page.component

    fun show(scene: HudScene) {
        page.root = Path.of(scene.root).normalize()
        page.call("show", "duke.showHud(${scene.json}, ${DukeBrowser.colours()})")
    }

    companion object {
        /** A preview, or null where the IDE runs without its browser. */
        fun create(parent: Disposable): HudPreview? = if (JBCefApp.isSupported()) HudPreview(parent) else null
    }
}
