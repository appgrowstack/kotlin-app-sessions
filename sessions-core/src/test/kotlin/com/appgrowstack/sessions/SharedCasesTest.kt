package com.appgrowstack.sessions

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the tables in `schema/cases/sessions` (copied by `scripts/sync-cases.sh`): the light record's and
 * the recording's (`mask_rects`, `segment`). Swift and TypeScript read the same files. `aggregate`
 * belongs to the server.
 */
class SharedCasesTest {
    companion object {
        val directory: File = File(File(requireNotNull(SharedCasesTest::class.java.getResource("/cases")) { "run scripts/sync-cases.sh" }.toURI()), "sessions")
        val notHere = setOf("aggregate")
        val all: List<Pair<File, Map<String, Any?>>> = directory.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
            .map { it to SessionJson.parse(it.readText()).asMap() }
        val here = all.filter { it.second["rule"] !in notHere }
    }

    @Test
    fun everyDeviceCaseIsRun() {
        assertEquals(98, all.size)
        assertEquals(91, here.size)
        assertEquals(5, here.count { it.second["rule"] == "mask_rects" })
        assertEquals(8, here.count { it.second["rule"] == "segment" })
    }

    @TestFactory
    fun sessionCases(): List<DynamicTest> = here.map { (file, json) ->
        DynamicTest.dynamicTest(file.nameWithoutExtension) {
            val actual = run(json["rule"] as String, json["input"].asMap())
            assertEquals(SessionJson.encode(json["expect"]), SessionJson.encode(actual), file.name)
        }
    }

    private fun run(rule: String, input: Map<String, Any?>): Map<String, Any?> = when (rule) {
        "split" -> {
            val steps = input["steps"].asList().map { it.asMap() }
                .map { it["t"].asLong() to SessionRules.LifecycleStep.valueOf((it["k"] as String).uppercase()) }
            val split = SessionRules.split(steps)
            mapOf(
                "session_of_step" to split.sessionOfStep,
                "sessions" to split.sessions.map { mapOf("start" to it.start, "end" to it.end, "by" to it.by?.wire) },
            )
        }
        "rage" -> {
            val window = input["window"].asMap()
            val taps = input["taps"].asList().map { it.asMap() }.map { SessionRules.Tap(it["t"].asLong(), it["x"].asDouble(), it["y"].asDouble()) }
            mapOf("rage" to SessionRules.rage(taps, SessionSize(window["w"].asDouble(), window["h"].asDouble()))
                .map { mapOf("t" to it.t, "x" to it.x, "y" to it.y, "count" to it.count) })
        }
        "dead" -> {
            val after = input["after"].asList().map { it.asMap() }.map {
                SessionRules.After(it["t"].asLong(), SessionRules.AfterKind.valueOf((it["k"] as String).uppercase()), it["fingerprint"] as String?)
            }
            mapOf("dead" to SessionRules.isDead(input["capturing"] as Boolean, input["tap"].asMap()["t"].asLong(), input["fingerprint_before"] as String, after))
        }
        "quick_back" -> {
            val steps = input["nav"].asList().map { it.asMap() }.map {
                SessionRules.NavigationStep(it["t"].asLong(), SessionRules.NavigationOp.of(it["op"] as String), it["screen"] as String,
                    (it["by"] as String?)?.let(SessionBackVia::of))
            }
            mapOf("events" to SessionRules.navigate(steps).map {
                when (it) {
                    is SessionRules.NavigationEvent.Screen -> mapOf("k" to "screen", "t" to it.t, "screen" to it.screen, "via" to it.via.wire)
                    is SessionRules.NavigationEvent.Back -> mapOf("k" to "back", "t" to it.t, "screen" to it.screen, "via" to it.via.wire)
                    is SessionRules.NavigationEvent.QuickBack -> mapOf("k" to "quick_back", "t" to it.t, "screen" to it.screen, "ms" to it.ms)
                }
            })
        }
        "end" -> {
            val decision = SessionRules.endReason(
                SessionRules.EndInput(
                    platform = SessionPlatform.of(input["platform"] as String),
                    androidApi = input["android_api"]?.asInt(),
                    previousRecord = input["previous_record"] as Boolean,
                    runningFlag = input["running_flag"] as Boolean,
                    lastEvent = input["last_event"] as String?,
                    metricKitCrash = input["metrickit_crash"] == true,
                    lateMetricKitCrash = input["late_metrickit_crash"] == true,
                    exitReason = input["exit_reason"] as String?,
                    crashHandler = input["crash_handler"] == true,
                ),
            )
            buildMap {
                put("reason", decision.reason.wire)
                decision.revised?.let { put("revised", it.wire) }
            }
        }
        "scroll" -> {
            val samples = input["samples"].asList().map { it.asMap() }.map {
                SessionRules.ScrollSample(it["offset_y"].asDouble(), it["height"].asDouble(), it["content_height"].asDouble(), it["row"]?.asInt())
            }
            val depth = SessionRules.scrollDepth(samples)
            buildMap {
                put("max_ratio", depth.maxRatio)
                depth.maxRow?.let { put("max_row", it) }
            }
        }
        "tap_normalize" -> {
            val tap = input["tap"].asMap()
            val w = input["window"].asMap()
            val n = SessionRules.normalizeTap(tap["x"].asDouble(), tap["y"].asDouble(),
                SessionRect(w["x"].asDouble(), w["y"].asDouble(), w["w"].asDouble(), w["h"].asDouble()))
            mapOf("x" to n.x, "y" to n.y, "orientation" to n.orientation.wire, "col" to n.col, "row" to n.row, "cell" to n.cell)
        }
        "keep_props" -> {
            @Suppress("UNCHECKED_CAST")
            val keep = input["keep_props"] as Map<String, List<String>>
            val kept = SessionRules.keepProps(
                SessionRules.EventSource.of(input["source"] as String), input["name"] as String,
                input["props"].asMap().mapValues { SessionValue.of(it.value) }, keep,
            )
            mapOf("props" to kept.props.mapValues { it.value.json }, "props_dropped" to kept.propsDropped)
        }
        "sampling" -> {
            val replay = input["replay"].asMap()
            val s = SessionRules.sampling(
                SessionRules.SamplingInput(
                    project = input["project"] as String, sessionId = input["session_id"] as String,
                    feature = SessionRules.Feature.of(input["feature"] as String),
                    replayEnabled = replay["enabled"] as Boolean, sample = replay["sample"].asDouble(),
                    dailyCap = replay["daily_cap"].asInt(), internalDevice = input["internal"] as Boolean,
                    recordedToday = input["recorded_today"].asInt(),
                    events = input["events"].asList().map { SessionEvent.fromJson(it.asMap()) },
                ),
            )
            buildMap {
                put("accept", s.accept)
                put("answer", s.answer?.wire)
                put("capture", s.capture)
                s.hash?.let { put("hash", it) }
                put("upload", s.upload.wire)
                s.uploadFromT?.let { put("upload_from_t", it) }
                put("priority", s.priority.map { it.wire })
                put("previous_session_upload", s.previousSessionUpload)
                put("session_replay", s.sessionReplay?.wire)
            }
        }
        "mask_rects" -> {
            val image = input["image"].asMap()
            val rects = input["rects"].asList().map { r ->
                r?.asMap()?.let { SessionRect(it["x"].asDouble(), it["y"].asDouble(), it["w"].asDouble(), it["h"].asDouble()) }
            }
            val plan = SessionRules.maskRects(input["scale"].asDouble(), SessionPixelSize(image["w"].asInt(), image["h"].asInt()), rects)
            buildMap {
                put("drop", plan.drop)
                plan.reason?.let { put("reason", it.value) }
                put("rects", plan.rects.map { mapOf("x" to it.x, "y" to it.y, "w" to it.w, "h" to it.h) })
            }
        }
        "segment" -> {
            val bytes = Base64.getDecoder().decode(input["bytes"] as String)
            try {
                val segment = SessionReplaySegment.decode(bytes)
                // Written again, it reads the same
                assertEquals(segment, SessionReplaySegment.decode(segment.encode()))
                mapOf(
                    "header" to segment.header(),
                    "images" to segment.frames.filterIsInstance<SessionReplayFrame.Image>().map { Base64.getEncoder().encodeToString(it.bytes) },
                )
            } catch (e: SessionReplaySegment.Invalid) {
                mapOf("error" to e.error.wire)
            }
        }
        else -> error("rule $rule is not run here")
    }
}
