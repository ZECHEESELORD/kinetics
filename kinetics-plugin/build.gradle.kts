import java.util.zip.ZipFile

plugins {
    java
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":kinetics-api"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")

    implementation("com.github.stephengold:jolt-jni-Windows64:5.2.0")
    runtimeOnly("com.github.stephengold:jolt-jni-Windows64:5.2.0:ReleaseSp")
    runtimeOnly("com.github.stephengold:jolt-jni-Linux64:5.2.0:ReleaseSp")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("Kinetics")
    archiveClassifier.set("")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
val verifyShadowJar = tasks.register("verifyShadowJar") {
    dependsOn(tasks.shadowJar)
    val archive = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(archive)

    doLast {
        val entries = ZipFile(archive.get().asFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        val required = setOf(
            "paper-plugin.yml",
            "META-INF/LICENSE-JOLT-JNI.txt",
            "META-INF/LICENSE-JOLT-PHYSICS.txt",
            "sh/harold/kinetics/api/Vec3.class",
            "windows/x86-64/com/github/stephengold/joltjni.dll",
            "linux/x86-64/com/github/stephengold/libjoltjni.so"
        )
        check(entries.containsAll(required)) {
            "Shaded jar is missing: ${required - entries}"
        }
        check(entries.none { it.startsWith("com/github/retrooper/packetevents/") }) {
            "PacketEvents must remain a server-provided dependency"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyShadowJar)
}
