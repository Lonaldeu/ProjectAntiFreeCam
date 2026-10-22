plugins {
    java
}

group = "me.lonaldeu"
version = "1.3.0"

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
    inputs.property("version", version) // so a version bump reaches plugin.yml in an existing build directory
    filesMatching("plugin.yml") { expand("version" to version) }
}

// A small plugin e2e/run.sh adds to the test servers, standing in for a vanish plugin and a per-player hologram plugin.
val e2ePlugin by sourceSets.creating {
    java.srcDir("e2e/testplugin/src")
    resources.srcDir("e2e/testplugin/resources")
    compileClasspath += sourceSets.main.get().compileClasspath
}
val e2ePluginJar by tasks.registering(Jar::class) {
    from(e2ePlugin.output)
    archiveFileName = "AfcTestPlugin.jar"
    destinationDirectory = layout.buildDirectory.dir("e2e")
}
tasks.build { dependsOn(e2ePluginJar) }
