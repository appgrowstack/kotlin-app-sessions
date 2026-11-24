package com.appgrowstack.sessions.compose

import android.graphics.Rect
import android.text.method.PasswordTransformationMethod
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.appgrowstack.sessions.SessionMaskMode
import com.appgrowstack.sessions.SessionMaskNode
import com.appgrowstack.sessions.SessionRect
import com.appgrowstack.sessions.SessionRules
import com.appgrowstack.sessions.isPasswordHint
import com.appgrowstack.sessions.isPasswordInputType
import com.appgrowstack.sessions.partsToHide

/**
 * What a frame of `window` paints over, in the window's pixels: only `Modifier.sessionMask()` and password
 * fields, which are always hidden; everything else is shown. A password field is an `EditText` whose input
 * type, transformation or autofill hint is a password's, or a Compose node with `Password` (a
 * `PasswordVisualTransformation`, `SecureTextField`) or the content type `Password` or `NewPassword`. Surface
 * and texture views are painted whole. Views inside Compose (`AndroidView`) are walked with the same rule.
 * Called on the main thread. A place it cannot find comes back as null, and the frame is dropped.
 */
internal object SessionMaskFinder {
    @Suppress("UNUSED_PARAMETER")
    fun rects(window: Window, mode: SessionMaskMode): List<SessionRect?> {
        val decor = window.peekDecorView() ?: return listOf(null)
        val out = mutableListOf<SessionRect?>()
        SessionMarks.masks(window).forEach { out.add(SessionRect(it.left.toDouble(), it.top.toDouble(), it.width.toDouble(), it.height.toDouble())) }
        out.addAll(SessionRules.partsToHide(ViewNode(decor)))
        return out
    }

    fun isPassword(view: EditText): Boolean =
        SessionRules.isPasswordInputType(view.inputType) ||
            view.transformationMethod is PasswordTransformationMethod ||
            view.autofillHints?.any { SessionRules.isPasswordHint(it) } == true

    // contentHints is internal in compose-ui, so compare with the public constants
    fun isPassword(type: ContentType?): Boolean = type == ContentType.Password || type == ContentType.NewPassword

    /** An Android view: a Compose view goes on into its semantics nodes, then into its `AndroidView`s. */
    private class ViewNode(private val view: View) : SessionMaskNode {
        override val shown: Boolean get() = view.visibility == View.VISIBLE

        override val hidesWhole: Boolean
            get() = view is SurfaceView || view is TextureView || (view is EditText && isPassword(view))

        override val bounds: SessionRect
            get() {
                if (view.width == 0 || view.height == 0) return EMPTY
                val r = Rect()
                // In the window's coordinates, clipped by the parents and with their transformations
                if (!view.getGlobalVisibleRect(r)) return EMPTY
                return SessionRect(r.left.toDouble(), r.top.toDouble(), r.width().toDouble(), r.height().toDouble())
            }

        override val children: List<SessionMaskNode>
            get() {
                val out = mutableListOf<SessionMaskNode>()
                if (view is RootForTest) out.add(SemanticsMaskNode(view.semanticsOwner.unmergedRootSemanticsNode))
                if (view is ViewGroup) (0 until view.childCount).mapNotNullTo(out) { i -> view.getChildAt(i)?.let { ViewNode(it) } }
                return out
            }
    }

    /** A Compose semantics node (unmerged). */
    private class SemanticsMaskNode(private val node: SemanticsNode) : SessionMaskNode {
        override val shown: Boolean get() = true

        override val hidesWhole: Boolean
            get() = SemanticsProperties.Password in node.config || isPassword(node.config.getOrNull(SemanticsProperties.ContentType))

        override val bounds: SessionRect
            get() {
                val b = node.boundsInWindow
                return SessionRect(b.left.toDouble(), b.top.toDouble(), b.width.toDouble(), b.height.toDouble())
            }

        override val children: List<SessionMaskNode> get() = node.children.map { SemanticsMaskNode(it) }
    }

    private val EMPTY = SessionRect(0.0, 0.0, 0.0, 0.0)
}
