package com.appgrowstack.sessions

/**
 * This package's name and version. The version is sent as `sdk_version` in each batch, and a
 * connector that sends batches to a server reports both, so the server can count which versions
 * call.
 */
public object SessionsSdk {
    /** The package name (`kotlin-app-sessions`). */
    public const val NAME: String = "kotlin-app-sessions"

    /** The released version. Must equal `version` in gradle.properties (SessionsSdkTest checks it). */
    public const val VERSION: String = "0.5.1"
}
