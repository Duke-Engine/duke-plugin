package uz.dukeengine.plugin.assets

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.util.text.EditDistance
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes

/** What an extension says a file is. */
enum class AssetKind(private val label: String, val extensions: List<String>) {
    MODEL("a model", listOf("glb", "gltf", "obj", "j3o")),
    IMAGE("an image", listOf("png", "jpg", "jpeg", "tga", "dds")),
    AUDIO("a sound", listOf("ogg", "wav", "mp3")),
    FONT("a font", listOf("fnt")),
    DATA("a data file", listOf("duke"));

    override fun toString() = "$label (${extensions.joinToString { ".$it" }})"

    companion object {
        fun of(path: String): AssetKind? {
            val extension = path.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }

        /** What a key's own name promises: `Model`, `CmdMoveIcon`, `TitleFont`. */
        fun named(key: String): AssetKind? = when {
            key.endsWith("model", ignoreCase = true) -> MODEL
            listOf("texture", "image", "icon", "border", "frame").any { key.endsWith(it, ignoreCase = true) } -> IMAGE
            key.endsWith("sound", ignoreCase = true) -> AUDIO
            key.endsWith("font", ignoreCase = true) -> FONT
            else -> null
        }
    }
}

/**
 * Asset paths as the game loads them: whole, from the resource root the `.duke` file sits in, the
 * classpath root jME loads from (`Model = models/heroes/rogue.glb`). No folder is put in front of
 * a name, so a game may keep its files in whatever structure it likes.
 */
object DukeAssets {
    /** Null outside a resource root: nothing to check against, so nothing is checked. */
    fun rootOf(element: PsiElement): VirtualFile? {
        val file = element.containingFile?.originalFile?.virtualFile ?: return null
        val index = ProjectFileIndex.getInstance(element.project)
        if (!index.isUnderSourceRootOfType(file, JavaModuleSourceRootTypes.RESOURCES)) return null
        return index.getSourceRootForFile(file)
    }

    /**
     * [path] as the game's classpath finds it: under [element]'s own resource root, else under another of the
     * project's — the kit's, `kit/effects/particles/star_04.png` from the game's files. Null outside a resource root.
     */
    fun resolve(element: PsiElement, path: String): VirtualFile? {
        val own = rootOf(element) ?: return null
        find(own, path)?.let { return it }
        return ProjectRootManager.getInstance(element.project).getModuleSourceRoots(JavaModuleSourceRootTypes.RESOURCES)
            .firstNotNullOfOrNull { root -> if (root == own) null else find(root, path) }
    }

    /**
     * [path] under [root], its letter case matched exactly: Linux CI tells `Models/` from `models/`,
     * so a lookup Windows would let through must fail here too.
     */
    fun find(root: VirtualFile, path: String): VirtualFile? {
        var at = root
        for (name in path.split('/')) {
            if (name.isEmpty()) continue
            at = at.findChild(name)?.takeIf { it.name == name } ?: return null
        }
        return at.takeIf { !it.isDirectory }
    }

    /** The same path in other letter case, or one a few typos away, among files of the same kind. */
    fun suggest(root: VirtualFile, value: String): String? {
        val kind = AssetKind.of(value)
        val candidates = filesUnder(root).filter { AssetKind.of(it) == kind && it != value }
        candidates.firstOrNull { it.equals(value, ignoreCase = true) }?.let { return it }
        return candidates.minByOrNull { EditDistance.levenshtein(it, value, true) }
            ?.takeIf { EditDistance.levenshtein(it, value, true) <= maxOf(2, value.length / 5) }
    }

    fun filesUnder(root: VirtualFile): List<String> {
        val paths = mutableListOf<String>()
        VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
            if (!file.isDirectory) VfsUtilCore.getRelativePath(file, root)?.let(paths::add)
            true
        }
        return paths
    }
}
