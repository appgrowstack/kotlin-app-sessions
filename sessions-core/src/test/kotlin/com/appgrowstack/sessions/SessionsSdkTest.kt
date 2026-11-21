package com.appgrowstack.sessions

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionsSdkTest {
    @Test
    fun versionMatchesGradleProperties() {
        val properties = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "gradle.properties") }
            .first { it.isFile && it.readText().contains(Regex("(?m)^version=")) }
        val version = Regex("(?m)^version=(.+)$").find(properties.readText())!!.groupValues[1].trim()
        assertEquals(version, SessionsSdk.VERSION)
        assertEquals("kotlin-app-sessions", SessionsSdk.NAME)
    }
}
