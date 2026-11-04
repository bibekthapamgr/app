// ProxyFlow's own Android Lint checks (threat model §6 item 3, M7): log statements carry no
// credentials, server names, destinations, payloads or exception messages. The app applies
// them with lintChecks(); `:app:lintDebug` checks every module it uses (checkDependencies).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Provided by Lint itself when it loads the checks.
    compileOnly(libs.lint.api)
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.tests)
    testImplementation(libs.junit4)
}
