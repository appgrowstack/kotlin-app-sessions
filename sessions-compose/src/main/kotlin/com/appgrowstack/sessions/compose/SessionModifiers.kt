package com.appgrowstack.sessions.compose

import android.view.View
import android.view.Window
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.appgrowstack.sessions.SessionRules
import java.util.concurrent.ConcurrentHashMap

/**
 * The marks placed by the modifiers below, with where they are in their window (px). The taps
 * read the targets; the recording reads the masks.
 */
public object SessionMarks {
    public sealed interface Kind {
        public data class Target(val name: String) : Kind
        public data object Mask : Kind
    }

    public data class Placed(val kind: Kind, val root: View, val bounds: Rect)

    private val placed = ConcurrentHashMap<Any, Placed>()

    internal fun put(key: Any, value: Placed) { placed[key] = value }
    internal fun remove(key: Any) { placed.remove(key) }

    /** The name of the smallest target under (`x`, `y`) in `window` (px). */
    public fun target(window: Window, x: Float, y: Float): String? {
        val root = window.peekDecorView() ?: return null
        return placed.values
            .filter { it.root === root && it.kind is Kind.Target && it.bounds.contains(androidx.compose.ui.geometry.Offset(x, y)) }
            .minByOrNull { it.bounds.width * it.bounds.height }
            ?.let { (it.kind as Kind.Target).name }
    }

    /** The bounds of every `Modifier.sessionMask()` in `window` (px). */
    public fun masks(window: Window): List<Rect> {
        val root = window.peekDecorView() ?: return emptyList()
        return placed.values.filter { it.root === root && it.kind == Kind.Mask }.map { it.bounds }
    }
}

private fun Modifier.sessionMark(kind: SessionMarks.Kind): Modifier = composed {
    val key = remember { Any() }
    val view = LocalView.current
    DisposableEffect(key) { onDispose { SessionMarks.remove(key) } }
    onGloballyPositioned { coordinates ->
        SessionMarks.put(key, SessionMarks.Placed(kind, view.rootView, coordinates.boundsInWindow()))
    }
}

/** Names this element in the taps on it (`tap.target`). `name` is lowercase letters, digits and `_`. */
public fun Modifier.sessionTarget(name: String): Modifier = sessionMark(SessionMarks.Kind.Target(name))

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
