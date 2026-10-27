import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-options")
    // The Chunker sources are large and deeply nested; a bigger compiler stack keeps javac happy.
    options.isFork = true
    options.forkOptions.jvmArgs = listOf("-Xss8m")
}

dependencies {
    // Provided by the application ClassLoader at runtime, never bundled into the engine dex.
    compileOnly(project(":api"))

    // Chunker runtime dependencies (shrunk + dexed by R8).
    implementation("com.google.guava:guava:33.7.1-jre")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("it.unimi.dsi:fastutil:8.5.19")
    implementation("net.jpountz.lz4:lz4:1.3.0")
    implementation("com.hivemc.leveldb:leveldb:1.1.0")
    implementation("com.hivemc.leveldb:leveldb-api:1.1.0")
    compileOnly("org.jetbrains:annotations:26.1.0")

    testImplementation(project(":api"))
}

// Desktop JVM sanity check for the trimmed engine: verifies the encoder table still loads and that the
// format list resolves. Android specific behaviour (dex loading) cannot be covered here.
tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Runs the trimmed engine on the desktop JVM (formats + detection)."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.frenetic.litechunk.engine.EngineSmokeTest")
    notCompatibleWithConfigurationCache("Ad-hoc verification task")
}

tasks.register<JavaExec>("roundTrip") {
    group = "verification"
    description = "Converts the Chunker integration fixtures on the desktop JVM: Java -> Bedrock -> Java."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.frenetic.litechunk.engine.EngineRoundTripTest")
    maxHeapSize = "2g"
    notCompatibleWithConfigurationCache("Ad-hoc verification task")
}

// Verifies the level.dat reader/writer against a real world on the desktop JVM: round trip, backup, allow list.
// Usage: gradlew :engine:levelTest --args="<worldDir>"
tasks.register<JavaExec>("levelTest") {
    group = "verification"
    description = "Runs LevelEditor against a copy of a real level.dat."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.frenetic.litechunk.engine.LevelEditorTest")
    notCompatibleWithConfigurationCache("Ad-hoc verification task")
}

// Dumps the actor (entity) records of a Bedrock world so entity conversion can be inspected without launching the
// game. Usage: gradlew :engine:actorDump --args="<worldDir> [maxSamples]"
tasks.register<JavaExec>("actorDump") {
    group = "verification"
    description = "Prints the entity records stored in a Bedrock world database."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.frenetic.litechunk.engine.ActorDump")
    notCompatibleWithConfigurationCache("Ad-hoc verification task")
}

// Runs the same diagnostic entry point the device uses, but on the desktop JVM and under the heap
// ceiling an Android `largeHeap` process actually gets. An emulator reproduces the heap limit
// faithfully but runs Chunker roughly 20x slower than a real device, which makes a large world take
// hours; this task answers the "does it still fit in 512 MB" question in minutes.
// Usage: gradlew :engine:engineCli --args="<worldDir> <workDir> <formatId> [resDir] [threads] [lean]"
tasks.register<JavaExec>("engineCli") {
    group = "verification"
    description = "Runs EngineCli on the desktop JVM with a 512 MB heap."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.frenetic.litechunk.engine.EngineCli")
    // Override with -PheapSize=320m to find where a world actually stops fitting, as opposed to where
    // the collector merely stopped bothering to sweep.
    maxHeapSize = (project.findProperty("heapSize") as String?) ?: "512m"
    notCompatibleWithConfigurationCache("Ad-hoc verification task")
}

// ---------------------------------------------------------------------------------------------
// Engine dex packaging
//
// The app never compiles against Chunker: the engine is dexed here and loaded through a
// DexClassLoader at runtime. Keeping the dex step as its own task means a normal
// `assembleDebug` never depends on the R8 command line.
//
//   gradlew :engine:engineDex     -> app/src/main/assets/chunker.jar
// ---------------------------------------------------------------------------------------------

fun resolveSdkDir(): File {
    val sdkDir: String? = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties")
            .takeIf { it.isFile }
            ?.let { file ->
                Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
            }

    requireNotNull(sdkDir) {
        "Android SDK not found: set ANDROID_HOME or add sdk.dir to local.properties"
    }
    return File(sdkDir)
}

fun resolveAndroidJar(): File {
    val platforms = File(resolveSdkDir(), "platforms")
        .listFiles { candidate -> File(candidate, "android.jar").isFile }
        ?.sortedBy { it.name }
        .orEmpty()
    require(platforms.isNotEmpty()) { "No Android platform installed" }
    return File(platforms.last(), "android.jar")
}

/** R8 ships inside the SDK build-tools, so no extra Maven dependency is needed. */
fun resolveD8Jar(): File {
    val buildTools = File(resolveSdkDir(), "build-tools")
        .listFiles { candidate -> File(candidate, "lib/d8.jar").isFile }
        ?.sortedBy { it.name }
        .orEmpty()
    require(buildTools.isNotEmpty()) { "No build-tools with lib/d8.jar installed" }
    return File(buildTools.last(), "lib/d8.jar")
}

val engineAssetsDir = rootProject.layout.projectDirectory.dir("app/src/main/assets")
val r8OutputDir = layout.buildDirectory.dir("r8")
val patchedDepsDir = layout.buildDirectory.dir("patched")

/**
 * Copies [source] into [target] while dropping [excludeEntry].
 * Used to remove leveldb's desktop-only ByteBufferSupport so the Android replacement (engine/src/main/java) is the only
 * definition R8 sees.
 */
fun repackJar(source: File, target: File, excludeEntry: String) {
    target.parentFile?.mkdirs()
    ZipInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { output ->
            var entry = input.nextEntry
            while (entry != null) {
                if (entry.name != excludeEntry) {
                    output.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) {
                        input.copyTo(output)
                    }
                    output.closeEntry()
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }
}

val engineDex = tasks.register<JavaExec>("engineDex") {
    group = "build"
    description = "Dexes the trimmed Chunker engine with R8 and writes app/src/main/assets/chunker.jar"
    dependsOn(tasks.named("jar"))
    mainClass.set("com.android.tools.r8.R8")
    notCompatibleWithConfigurationCache("Invokes the R8 command line interface")

    val engineJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val apiJar = project(":api").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtime = configurations.named("runtimeClasspath")

    val androidJar = resolveAndroidJar()
    val d8Jar = resolveD8Jar()
    classpath = files(d8Jar)

    // Declared inputs/outputs let Gradle skip the ~20 s R8 run when nothing changed. Without them the task
    // always re-runs, and because R8's output is not byte-stable the committed chunker.jar would show up as
    // modified in git after every single build.
    inputs.files(engineJar, apiJar)
    inputs.files(runtime)
    inputs.files(androidJar, d8Jar)
    inputs.file(file("engine.pro"))
    outputs.file(engineAssetsDir.file("chunker.jar"))
    outputs.dir(r8OutputDir)
    outputs.dir(patchedDepsDir)

    doFirst {
        val output = r8OutputDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val patchDir = patchedDepsDir.get().asFile
        patchDir.deleteRecursively()
        patchDir.mkdirs()

        val dependencyJars = runtime.get().files
            .filter { it.name.endsWith(".jar") }
            .sortedBy { it.name }
            .map { jar ->
                val isLeveldb = jar.name.startsWith("leveldb-") && !jar.name.startsWith("leveldb-api")
                if (!isLeveldb) {
                    return@map jar
                }
                val patched = File(patchDir, jar.name)
                repackJar(jar, patched, "org/iq80/leveldb/fileenv/ByteBufferSupport.class")
                logger.lifecycle("engineDex: patched ${jar.name} (ByteBufferSupport -> Android replacement)")
                patched
            }

        args = buildList {
            add("--min-api")
            add("27")
            add("--lib")
            add(androidJar.absolutePath)
            add("--lib")
            add(apiJar.get().asFile.absolutePath)
            add("--pg-conf")
            add(file("engine.pro").absolutePath)
            add("--output")
            add(output.absolutePath)
            add(engineJar.get().asFile.absolutePath)
            dependencyJars.forEach { add(it.absolutePath) }
        }
    }

    doLast {
        val dexFiles = r8OutputDir.get().asFile
            .listFiles { file -> file.name.matches(Regex("classes\\d*\\.dex")) }
            .orEmpty()
            .sortedBy { it.name }
        check(dexFiles.isNotEmpty()) { "R8 produced no dex output in ${r8OutputDir.get().asFile}" }

        val target = engineAssetsDir.file("chunker.jar").asFile
        target.parentFile.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            dexFiles.forEach { dex ->
                zip.putNextEntry(ZipEntry(dex.name))
                dex.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        logger.lifecycle(
            "engineDex: wrote ${target.absolutePath} (${target.length() / 1024} KiB, " +
                "${dexFiles.size} dex file(s))"
        )
    }
}
