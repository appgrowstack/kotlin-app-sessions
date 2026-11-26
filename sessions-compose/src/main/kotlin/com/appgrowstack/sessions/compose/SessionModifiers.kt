package com.appgrowstack.sessions.compose

import android.view.View
import android.view.Window
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appgrowstack.sessions.AppSessions
import com.appgrowstack.sessions.SessionRect
import com.appgrowstack.sessions.SessionRules
import com.appgrowstack.sessions.tapTarget
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The marks placed by the modifiers below, with where they are in their window (px). The taps
 * read the targets; the recording reads the masks.
 */
public object SessionMarks {
    public sealed interface Kind {
        public data class Target(val name: String) : Kind
        public data object Layer : Kind
        public data object Mask : Kind
    }

    /**
     * [layers] is the path of `Modifier.sessionLayer()`s the mark is in, outermost first (a layer's own
     * mark ends with itself). [order] is when the mark was first composed: later is drawn in front.
     */
    public data class Placed(val kind: Kind, val root: View, val bounds: Rect, val layers: List<String> = emptyList(), val order: Long = 0)

    private val placed = ConcurrentHashMap<Any, Placed>()
    private val composed = AtomicLong()

    internal fun put(key: Any, value: Placed) { placed[key] = value }
    internal fun remove(key: Any) { placed.remove(key) }
    internal fun nextOrder(): Long = composed.incrementAndGet()

    /**
     * The name of a tap at (`x`, `y`) in `window` (px) ([SessionRules.tapTarget]): only the targets of
     * the front layer (`Modifier.sessionLayer`) under the finger count, so a row under a dialog drawn in
     * a `Box` does not name a tap on the dialog.
     */
    public fun target(window: Window, x: Float, y: Float): String? {
        val root = window.peekDecorView() ?: return null
        val marks = placed.values.filter { it.root === root }.sortedBy { it.order }.mapNotNull { mark ->
            val name = when (val kind = mark.kind) {
                is Kind.Target -> kind.name
                Kind.Layer -> null
                Kind.Mask -> return@mapNotNull null
            }
            val b = mark.bounds
            SessionRules.TapMark(name, SessionRect(b.left.toDouble(), b.top.toDouble(), b.width.toDouble(), b.height.toDouble()), mark.layers)
        }
        return SessionRules.tapTarget(x.toDouble(), y.toDouble(), marks)
    }

    /** The bounds of every `Modifier.sessionMask()` in `window` (px). */
    public fun masks(window: Window): List<Rect> {
        val root = window.peekDecorView() ?: return emptyList()
        return placed.values.filter { it.root === root && it.kind == Kind.Mask }.map { it.bounds }
    }
}

/** The layers (`Modifier.sessionLayer()`) an element is in, outermost first. */
private val LocalSessionLayers = compositionLocalOf<List<String>> { emptyList() }

private fun Modifier.sessionMark(kind: SessionMarks.Kind, layers: List<String>? = null): Modifier = composed {
    val key = remember { Any() }
    val order = remember { SessionMarks.nextOrder() }
    val view = LocalView.current
    val path = layers ?: LocalSessionLayers.current
    DisposableEffect(key) { onDispose { SessionMarks.remove(key) } }
    onGloballyPositioned { coordinates ->
        SessionMarks.put(key, SessionMarks.Placed(kind, view.rootView, coordinates.boundsInWindow(), path, order))
    }
}

/** Names this element in the taps on it (`tap.target`). `name` is lowercase letters, digits and `_`. */
public fun Modifier.sessionTarget(name: String): Modifier = sessionMark(SessionMarks.Kind.Target(name))

/**
 * Marks [content] as a layer drawn over the screen in the same window (a dialog, an onboarding page, an
 * offer, a picker or a bar laid on top in a `Box`). While the layer is under the finger, only the
 * `Modifier.sessionTarget`s inside it name the tap; a tap on the layer where none of its targets is has
 * no name. Without it, a row under the dialog names a tap on the dialog. [modifier] sizes the layer as
 * the part of the screen it covers (its dimmed backdrop too). A layer inside a layer is in front of it.
 * A `Dialog` or a `Popup` is a window of its own and needs none.
 */
@Composable
public fun SessionLayer(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val id = remember { UUID.randomUUID().toString() }
    val layers = LocalSessionLayers.current + id
    Box(modifier.sessionMark(SessionMarks.Kind.Layer, layers)) {
        CompositionLocalProvider(LocalSessionLayers provides layers) { content() }
    }
}

/** Hides this element from the session recording: its place is painted black in every frame. */
public fun Modifier.sessionMask(): Modifier = sessionMark(SessionMarks.Kind.Mask)

/**
 * While this screen is shown, and while it lies under the shown screen (a screen pushed on it with
 * Navigation Compose), the recording takes no frames (blank frames `no_capture` instead). Put it on the
 * screen's root element. The hold follows the screen's lifecycle owner ([NoCaptureHold]).
 */
public fun Modifier.sessionNoCapture(): Modifier = composed {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val hold = AppSessions.current?.holdNoCapture() ?: return@DisposableEffect onDispose {}
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> hold.ownerResumed()
                    Lifecycle.Event.ON_DESTROY -> hold.ownerDestroyed()
                    else -> Unit
                }
                if (hold.ended) source.lifecycle.removeObserver(this)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            hold.left(ownerResumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            if (hold.ended) owner.lifecycle.removeObserver(observer)
        }
    }
    this
}

/** Records how far a `verticalScroll(state)` was scrolled as the list `name`. Put it on the same element. */
public fun Modifier.sessionScroll(state: ScrollState, name: String): Modifier = composed {
    val viewport = remember { IntArray(1) }
    LaunchedEffect(state, name) {
        snapshotFlow { state.value to state.maxValue }.collect { (value, max) ->
            val height = viewport[0].toDouble()
            if (height > 0) AppSessions.current?.scroll(SessionRules.ScrollSample(value.toDouble(), height, max + height), name)
        }
    }
    onSizeChanged { viewport[0] = it.height }
}

/**
 * Records how far a `LazyColumn(state = state)` was scrolled as the list `name`: the depth is the
 * share of the items up to the last one shown, and `max_row` is the last item shown.
 */
public fun Modifier.sessionScroll(state: LazyListState, name: String): Modifier = composed {
    LaunchedEffect(state, name) {
        snapshotFlow { state.layoutInfo.let { Triple(it.visibleItemsInfo.firstOrNull()?.index, it.visibleItemsInfo.lastOrNull()?.index, it.totalItemsCount) } }
            .collect { (first, last, total) ->
                if (first == null || last == null || total <= 0) return@collect
                val shown = (last - first + 1).toDouble()
                AppSessions.current?.scroll(SessionRules.ScrollSample(first.toDouble(), shown, total.toDouble(), last), name)
            }
    }
    this
}
