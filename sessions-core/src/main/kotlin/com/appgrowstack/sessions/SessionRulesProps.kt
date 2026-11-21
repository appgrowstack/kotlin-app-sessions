package com.appgrowstack.sessions

import com.appgrowstack.sessions.SessionRules.EventSource
import com.appgrowstack.sessions.SessionRules.KeptProps

// SessionRules: Which props of an app event are kept.

/**
 * Which of an app event's props are kept (sessions.md 9.4). A null value stands for one that is
 * not text, a number or yes/no (a list, an object, null): it is dropped without the flag.
 */
public fun SessionRules.keepProps(source: EventSource, name: String, props: Map<String, SessionValue?>, keepProps: Map<String, List<String>>): KeptProps {
    val chosen = when {
        source == EventSource.COMMON || name.startsWith("campaign_") || name.startsWith("campaigns_") -> props
        keepProps.containsKey(name) -> props.filterKeys { it in keepProps.getValue(name) }
        else -> emptyMap()
    }
    val kept = linkedMapOf<String, SessionValue>()
    var dropped = false
    for ((key, value) in chosen) {
        if (value == null) continue
        // NaN and infinity are not JSON numbers: kept, they would make every later save of the state throw
        if (value is SessionValue.Decimal && !value.value.isFinite()) continue
        if (value is SessionValue.Text && value.value.length > MAX_PROP_LENGTH) {
            dropped = true
            continue
        }
        kept[key] = value
    }
    return KeptProps(kept, dropped)
}
