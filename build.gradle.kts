plugins {
    id("com.gradleup.shadow") version "9.5.1" apply false
}

allprojects {
    group = "sh.harold.kinetics"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
