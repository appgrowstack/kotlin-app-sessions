package com.appgrowstack.sessions

import com.appgrowstack.sessions.SessionRules.NavigationEvent
import com.appgrowstack.sessions.SessionRules.NavigationStep

// SessionRules: Moves between screens and quick backs.

/** Moves between screens → the `screen`, `back` and `quick_back` events. See [Navigator]. */
public fun SessionRules.navigate(steps: List<NavigationStep>): List<NavigationEvent> {
    val navigator = Navigator()
    return steps.flatMap { navigator.move(it) }
}

/**
 * Moves between screens one at a time: the screen shown, and for `pop` and `dismiss` the `back`,
 * the `quick_back` when it came within 2000 ms of entering, and the screen it went back to.
 */
public class Navigator {
    public var current: String? = null
        private set
    public var enteredAt: Long? = null
        private set

    public fun move(step: SessionRules.NavigationStep): List<SessionRules.NavigationEvent> {
        val events = mutableListOf<SessionRules.NavigationEvent>()
        val via = when (step.op) {
            SessionRules.NavigationOp.LAUNCH -> SessionScreenVia.LAUNCH
            SessionRules.NavigationOp.PUSH -> SessionScreenVia.PUSH
            SessionRules.NavigationOp.TAB -> SessionScreenVia.TAB
            SessionRules.NavigationOp.SHEET -> SessionScreenVia.SHEET
            SessionRules.NavigationOp.POP, SessionRules.NavigationOp.DISMISS -> {
                val screen = current
                val entered = enteredAt
                if (screen != null) {
                    events.add(SessionRules.NavigationEvent.Back(step.t, screen, step.by ?: SessionBackVia.BUTTON))
                    if (entered != null && step.t - entered <= SessionRules.QUICK_BACK_MS) {
                        events.add(SessionRules.NavigationEvent.QuickBack(step.t, screen, step.t - entered))
                    }
                }
                SessionScreenVia.POP
            }
        }
        events.add(SessionRules.NavigationEvent.Screen(step.t, step.screen, via))
        current = step.screen
        enteredAt = step.t
        return events
    }

    /** Enters a screen without a known move (a new session on the same screen). */
    public fun enter(screen: String, t: Long) {
        current = screen
        enteredAt = t
    }
}
