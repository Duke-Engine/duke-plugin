package uz.dukeengine.plugin.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefPostDataElement
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.awt.Color
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/** A page of the IDE's own browser that draws and plays a [PreviewScene]. */
class ModelPreview private constructor(parent: Disposable) {
    private val page = DukeBrowser(parent, "viewer/index.html") { _, _ -> }

    val component: JComponent get() = page.component

    fun show(scene: PreviewScene) {
        page.root = Path.of(scene.root).normalize()
        page.call("show", "duke.show(${scene.json()}, ${DukeBrowser.colours()})")
    }

    fun play(clip: String) = page.call("play", "duke.play(${Json.string(clip)})")

    companion object {
        /** A preview, or null where the IDE runs without its browser. */
        fun create(parent: Disposable): ModelPreview? = if (JBCefApp.isSupported()) ModelPreview(parent) else null
    }
}

/**
 * A page of the IDE's own browser, served in-process from `http://duke.preview/` — a host that exists only for this
 * browser, so nothing listens on a port and nothing leaves the machine: the plugin's `viewer/`, the game's resource
 * root under `res/`, and what the page posts to `do/<what>`, handed to [act] with its body on the UI thread.
 */
class DukeBrowser(parent: Disposable, page: String, private val act: (String, String) -> Unit) {
    private val browser = JBCefBrowser()

    @Volatile
    var root: Path? = null
    private var ready = false

    /** The last of each kind of call made before the page was there to take it. */
    private val waiting = LinkedHashMap<String, String>()

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, browser)
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun getResourceRequestHandler(
                browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, isNavigation: Boolean, isDownload: Boolean,
                requestInitiator: String?, disableDefaultHandling: BoolRef?,
            ): CefResourceRequestHandler? {
                val path = PreviewFiles.pathOf(request?.url) ?: return null
                if (path.startsWith("do/")) {
                    val body = bodyOf(request)
                    ApplicationManager.getApplication().invokeLater { act(path.removePrefix("do/"), body) }
                    return object : CefResourceRequestHandlerAdapter() {
                        override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler =
                            Served(ByteArray(0), "text/plain")
                    }
                }
                return object : CefResourceRequestHandlerAdapter() {
                    override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler =
                        Served(PreviewFiles.bytes(path, root), PreviewFiles.mime(path))
                }
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                ApplicationManager.getApplication().invokeLater {
                    ready = true
                    waiting.values.forEach(::run)
                    waiting.clear()
                }
            }
        }, browser.cefBrowser)
        browser.loadURL(PreviewFiles.ORIGIN + page)
    }

    /** [code] run in the page once it is there; a later call of the same [kind] made before then replaces this one. */
    fun call(kind: String, code: String) {
        if (ready) run(code) else waiting[kind] = code
    }

    private fun run(code: String) = browser.cefBrowser.executeJavaScript(code, browser.cefBrowser.url, 0)

    /** What a page posted: its body, as text. */
    private fun bodyOf(request: CefRequest?): String {
        val elements = java.util.Vector<CefPostDataElement>()
        request?.postData?.getElements(elements)
        return elements.joinToString("") { element ->
            val bytes = ByteArray(element.bytesCount)
            element.getBytes(bytes.size, bytes)
            String(bytes, Charsets.UTF_8)
        }
    }

    companion object {
        /** The tool window's own colours, for a page to be drawn in. */
        fun colours(): String = Json.of(mapOf(
        "background" to css(UIUtil.getPanelBackground()),
        "foreground" to css(UIUtil.getLabelForeground()),
        "muted" to css(UIUtil.getContextHelpForeground()),
        "border" to css(JBColor.border()),
        "accent" to css(JBUI.CurrentTheme.Focus.focusColor()),
        ))

        private fun css(colour: Color) = "#%06X".format(colour.rgb and 0xFFFFFF)
    }
}

/** What `http://duke.preview/` serves: `viewer/` from the plugin, `res/` from the game's resource root. */
internal object PreviewFiles {
    const val ORIGIN = "http://duke.preview/"

    /** The path a request asks for, or null when it is not for this host. */
    fun pathOf(url: String?): String? {
        if (url == null || !url.startsWith(ORIGIN)) return null
        return runCatching { URI(url).path.removePrefix("/") }.getOrNull()
    }

    fun bytes(path: String, root: Path?): ByteArray? = when {
        path.startsWith("viewer/") -> PreviewFiles::class.java.getResourceAsStream("/$path")?.use { it.readBytes() }
        path.startsWith("res/") && root != null -> {
            val file = root.resolve(path.removePrefix("res/")).normalize()
            // Nothing above the root is the game's to show: `res/../../` asks for somebody else's file.
            if (file.startsWith(root) && Files.isRegularFile(file)) Files.readAllBytes(file) else null
        }
        else -> null
    }

    fun mime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"
        "js" -> "text/javascript"
        "glb" -> "model/gltf-binary"
        "gltf" -> "model/gltf+json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}

/** One response of the whole of [bytes], or a 404 when there are none. */
private class Served(private val bytes: ByteArray?, private val mime: String) : CefResourceHandlerAdapter() {
    private var at = 0

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        callback?.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse?, length: IntRef?, redirectUrl: StringRef?) {
        response?.setStatus(if (bytes == null) 404 else 200)
        response?.setMimeType(mime)
        length?.set(bytes?.size ?: 0)
    }

    override fun readResponse(out: ByteArray?, wanted: Int, read: IntRef?, callback: CefCallback?): Boolean {
        val bytes = bytes
        if (out == null || bytes == null || at >= bytes.size) {
            read?.set(0)
            return false
        }
        val count = minOf(wanted, bytes.size - at)
        System.arraycopy(bytes, at, out, 0, count)
        at += count
        read?.set(count)
        return true
    }
}
