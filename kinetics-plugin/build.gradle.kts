plugins {
    java
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":kinetics-api"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")

    implementation("com.github.stephengold:jolt-jni-Windows64:5.1.0")
    runtimeOnly("com.github.stephengold:jolt-jni-Windows64:5.1.0:ReleaseSp")
    runtimeOnly("com.github.stephengold:jolt-jni-Linux64:5.1.0:ReleaseSp")
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