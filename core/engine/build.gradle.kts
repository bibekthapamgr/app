// The packet engine for Android: the Go engine (engine/, ADR-0001..0005) as the
// gomobile AAR that engine/build-aar.sh builds, unpacked here into a classes
// jar and per-ABI libraries, and a Kotlin API around it (ProxyEngine). The
// JVM tests use a fake native boundary; the instrumented tests run the real
// engine on emulators against mock proxies. core/vpn runs it on the VPN interface (M5).
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

/** Unpacks the AAR: classes.jar to compile against, jni/<abi>/libgojni.so to package. */
abstract class UnpackEngineAar : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val jniLibs: DirectoryProperty

    @get:OutputFile
    abstract val classesJar: RegularFileProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun unpack() {
        val file = aar.files.singleOrNull()?.takeIf { it.isFile } ?: throw GradleException(
            "engine/build/pfengine.aar is missing. Build it with engine/build-aar.sh (Go and the Android NDK), " +
                "or put the pfengine-aar artifact of a CI run there.",
        )
        fileSystem.sync {
            from(archives.zipTree(file)) {
                include("jni/**")
                eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
            }
            includeEmptyDirs = false
            into(jniLibs)
        }
        fileSystem.copy {
            from(archives.zipTree(file)) { include("classes.jar") }
            into(classesJar.get().asFile.parentFile)
        }
    }
}

val unpackEngineAar = tasks.register<UnpackEngineAar>("unpackEngineAar") {
    aar.from(rootDir.resolve("engine/build/pfengine.aar"))
    jniLibs.set(layout.buildDirectory.dir("pfengine/jni"))
    classesJar.set(layout.buildDirectory.file("pfengine/classes/classes.jar"))
}

android {
    namespace = "app.proxyflow.core.engine"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // gomobile's Java classes are called from native code by name.
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The app's lint rules (warnings are errors). The app's own lint covers this module too since M5;
    // this keeps the module checked on its own.
    lint {
        abortOnError = true
        warningsAsErrors = true
        lintConfig = rootProject.file("config/lint/lint.xml")
        xmlReport = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(unpackEngineAar, UnpackEngineAar::jniLibs)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:model"))
    // api: the gomobile types (Platform) appear in ProxyEngine's signatures, which core/vpn calls.
    api(files(unpackEngineAar.flatMap { it.classesJar }))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
