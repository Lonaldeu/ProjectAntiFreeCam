plugins {
    java
}

group = "me.lonaldeu"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    // Provided by the server at runtime; only needed to compile the pipeline handler.
    compileOnly("io.netty:netty-transport:4.1.118.Final")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

// Our reflection uses Mojang names; tell Paper not to remap the jar as if it used Spigot's.
tasks.jar {
    manifest { attributes("paperweight-mappings-namespace" to "mojang") }
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to version) }
}
