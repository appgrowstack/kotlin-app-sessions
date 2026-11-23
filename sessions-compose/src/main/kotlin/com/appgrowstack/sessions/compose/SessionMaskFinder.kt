package com.appgrowstack.sessions.compose

import android.graphics.Rect
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.appgrowstack.sessions.SessionMaskMode
import com.appgrowstack.sessions.SessionRect

/**
 * What a frame of `window` paints over, in the window's pixels (sessions.md 3.3). Always: text fields
 * (`EditText`, and Compose nodes with `EditableText` or `SetText`), password fields, `Modifier.sessionMask()`,
 * web views, surface and texture views, and every view inside Compose (`AndroidView`: Compose cannot see
 * into it). With `TEXT_AND_IMAGES` also every `TextView`, `ImageView`, and Compose node with text or the
 * image role. Called on the main thread. A place it cannot find comes back as null, and the frame is dropped.
 */
internal object SessionMaskFinder {
    fun rects(window: Window, mode: SessionMaskMode): List<SessionRect?> {
        val decor = window.peekDecorView() ?: return listOf(null)
        val out = mutableListOf<SessionRect?>()
        SessionMarks.masks(window).forEach { out.add(SessionRect(it.left.toDouble(), it.top.toDouble(), it.width.toDouble(), it.height.toDouble())) }
        walk(decor, mode, out)
        return out
    }

    private fun walk(view: View, mode: SessionMaskMode, out: MutableList<SessionRect?>) {
        if (view.visibility != View.VISIBLE) return
        when {
            view is RootForTest -> {
                compose(view.semanticsOwner.unmergedRootSemanticsNode, mode, out)
                // The views of `AndroidView` sit under the Compose view (in a holder); hide each whole
                if (view is ViewGroup) children(view).forEach { child ->
                    if (child is ViewGroup) children(child).forEach { add(it, out) } else add(child, out)
                }
                return
            }
            view is WebView || view is SurfaceView || view is TextureView -> { add(view, out); return }
            view is EditText -> { add(view, out); return }
            mode == SessionMaskMode.TEXT_AND_IMAGES && (view is TextView || view is ImageView) -> { add(view, out); return }
        }
        if (view is ViewGroup) children(view).forEach { walk(it, mode, out) }
    }

    private fun compose(node: SemanticsNode, mode: SessionMaskMode, out: MutableList<SessionRect?>) {
        val config = node.config
        val input = SemanticsProperties.EditableText in config || SemanticsActions.SetText in config || SemanticsProperties.Password in config
        val textOrImage = mode == SessionMaskMode.TEXT_AND_IMAGES &&
            (SemanticsProperties.Text in config || config.getOrNull(SemanticsProperties.Role) == Role.Image)
        if (input || textOrImage) {
            val b = node.boundsInWindow
            out.add(SessionRect(b.left.toDouble(), b.top.toDouble(), b.width.toDouble(), b.height.toDouble()))
        }
        node.children.forEach { compose(it, mode, out) }
    }

    private fun add(view: View, out: MutableList<SessionRect?>) {
        if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) return
        val r = Rect()
        // In the window's coordinates, clipped by the parents and with their transformations
        if (!view.getGlobalVisibleRect(r)) return
        out.add(SessionRect(r.left.toDouble(), r.top.toDouble(), r.width().toDouble(), r.height().toDouble()))
    }

    private fun children(group: ViewGroup): List<View> = (0 until group.childCount).mapNotNull { group.getChildAt(it) }
}
