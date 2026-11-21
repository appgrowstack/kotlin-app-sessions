package com.appgrowstack.sessions

import com.appgrowstack.sessions.SessionRules.ScrollDepth
import com.appgrowstack.sessions.SessionRules.ScrollSample

// SessionRules: How deep a list was scrolled.

public fun SessionRules.scrollDepth(samples: List<ScrollSample>): ScrollDepth =
    ScrollDepth(samples.maxOfOrNull { it.ratio } ?: 0, samples.mapNotNull { it.row }.maxOrNull())
