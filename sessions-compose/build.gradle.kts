// Android: 窓のタップ（Window.Callback）・前と裏・戻るのキー・ApplicationExitInfo・API 30 より前のクラッシュの受け手・
// メインスレッドの止まり・Compose のマーク（Modifier.sessionTarget・sessionScroll・sessionMask・sessionNoCapture）。
// 依存は sessions-core・Android の SDK・Compose の ui と foundation だけ。AGP 9 は Kotlin を内蔵しているので kotlin.android は付けない
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.appgrowstack.sessions.compose"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":sessions-core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "sessions-compose"
        }
    }
}
