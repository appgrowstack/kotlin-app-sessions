pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kotlin-app-sessions"

// Kotlin/JVM だけ。Android の SDK を要らない（判定の表・記録・送り先・保存。アプリの :state と headless のテストからも使える）
include(":sessions-core")

// Android（窓のタップ・前と裏・ApplicationExitInfo・Compose のマーク）。Android の SDK がある所だけで入れる。
// Android Studio が local.properties に sdk.dir を書いた所では自動で入る。コマンドラインでは -Psessions.withAndroid=true
val sdkDirWritten = file("local.properties").let { it.exists() && it.readLines().any { line -> line.startsWith("sdk.dir=") } }
if (sdkDirWritten || providers.gradleProperty("sessions.withAndroid").orNull == "true") {
    include(":sessions-compose")
}
