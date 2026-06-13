plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    compileOnlyApi("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            artifactId = "kinetics-api"
        }
    }
}
