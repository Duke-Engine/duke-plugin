package uz.duke.plugin.map

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The picture of a map that sits beside it: the map from above, a square a cell, with what stands on it marked.
 *
 * <p>A map folder holds one — `preview.png` — and it is what a screen where a map is chosen shows. Generals keeps a
 * `.tga` beside every map and Warcraft III bakes one into the archive, both for the same reason: a player choosing
 * between maps is choosing between places, and a place is a shape before it is a name.
 *
 * <p>Drawn from above rather than from the 3D view on purpose. A preview is read at the size of a thumbnail, where a
 * camera angle costs more than it shows — and this way a map has its picture whether or not the IDE has a browser.
 */
internal object MapPreview {

    const val NAME = "preview.png"

    /** How wide or tall the picture is at its longer side; a cell is whole pixels, so the picture may come out under it. */
    private const val SIDE = 512

    fun picture(map: MapModel, side: Int = SIDE): BufferedImage {
        val cell = (side / maxOf(1, maxOf(map.width, map.height))).coerceAtLeast(1)
        val image = BufferedImage(maxOf(1, map.width * cell), maxOf(1, map.height * cell), BufferedImage.TYPE_INT_RGB)
        val g2 = image.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = MapCanvas.BACKGROUND
            g2.fillRect(0, 0, image.width, image.height)
            for (y in 0 until map.height) {
                for (x in 0 until map.width) {
                    g2.color = MapCanvas.groundOf(map.charAt(x, y), map.solid)
                    g2.fillRect(x * cell, y * cell, cell, cell)
                }
            }
            // The lists first and the ones a map has one of over them, as the canvas draws them: the way in is not
            // hidden under a monster standing on it.
            for (layer in map.layers.sortedBy { it.single }) {
                for (thing in layer.things) {
                    val size = (cell * if (layer.single) 1.4f else 0.8f).toInt().coerceAtLeast(2)
                    g2.color = MapCanvas.colourOf(layer, thing.kind)
                    g2.fillOval(thing.x * cell + cell / 2 - size / 2, thing.y * cell + cell / 2 - size / 2, size, size)
                }
            }
        } finally {
            g2.dispose()
        }
        return image
    }

    /** The picture written beside the map, replacing the one there: the file, or null when the map is in no folder. */
    fun save(file: VirtualFile, map: MapModel): VirtualFile? {
        val folder = file.parent ?: return null
        val bytes = ByteArrayOutputStream().also { ImageIO.write(picture(map), "png", it) }.toByteArray()
        return WriteAction.computeAndWait<VirtualFile, Exception> {
            val target = folder.findChild(NAME) ?: folder.createChildData(this, NAME)
            target.setBinaryContent(bytes)
            target
        }
    }
}
