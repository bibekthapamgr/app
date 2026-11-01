// The VPN layer (ADR-0010): ProxyFlowVpnService, the connection session that
// runs the engine on Android's VPN interface, the app's ConnectionController
// and the connection notifications. Screens reach it only through
// ConnectionController (core/data). The JVM tests drive the session with
// fakes; the app's instrumented tests run the real service on emulators.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.proxyflow.core.vpn"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
}
