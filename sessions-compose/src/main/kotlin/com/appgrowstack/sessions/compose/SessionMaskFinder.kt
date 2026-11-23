package com.appgrowstack.sessions.compose

import android.graphics.Rect
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import com.appgrowstack.sessions.SessionMaskMode
import com.appgrowstack.sessions.SessionRect

/**
 * What a frame of `window` paints over, in the window's pixels: only `Modifier.sessionMask()` and password
 * fields (an `EditText` whose input type or transformation is a password's, a Compose node with
 * `Password`); everything else is shown. Surface and texture views are painted whole. Views inside
 * Compose (`AndroidView`) are walked with the same rule. Called on the main thread. A place it cannot find
 * comes back as null, and the frame is dropped.
 */
internal object SessionMaskFinder {
    @Suppress("UNUSED_PARAMETER")
    fun rects(window: Window, mode: SessionMaskMode): List<SessionRect?> {
        val decor = window.peekDecorView() ?: return listOf(null)
        val out = mutableListOf<SessionRect?>()
        SessionMarks.masks(window).forEach { out.add(SessionRect(it.left.toDouble(), it.top.toDouble(), it.width.toDouble(), it.height.toDouble())) }
        walk(decor, out)
        return out
    }

    private fun walk(view: View, out: MutableList<SessionRect?>) {
        if (view.visibility != View.VISIBLE) return
        when {
            view is RootForTest -> compose(view.semanticsOwner.unmergedRootSemanticsNode, out)
            view is SurfaceView || view is TextureView -> { add(view, out); return }
            view is EditText && isPassword(view) -> { add(view, out); return }
        }
        // Also the views of `AndroidView` under a Compose view, with the same rule
        if (view is ViewGroup) children(view).forEach { walk(it, out) }
    }

    private fun isPassword(view: EditText): Boolean {
        val type = view.inputType
        val klass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val text = klass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        val number = klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return text || number || view.transformationMethod is PasswordTransformationMethod
    }

    private fun compose(node: SemanticsNode, out: MutableList<SessionRect?>) {
        if (SemanticsProperties.Password in node.config) {
            val b = node.boundsInWindow
            out.add(SessionRect(b.left.toDouble(), b.top.toDouble(), b.width.toDouble(), b.height.toDouble()))
        }
        node.children.forEach { compose(it, out) }
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
