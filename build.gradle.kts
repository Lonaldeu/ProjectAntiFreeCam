plugins {
    kotlin("jvm") version "2.2.20"
    id("com.gradleup.shadow") version "9.2.1"
}

group = "me.lonaldeu"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "papermc-snapshots"
        url = uri("https://repo.papermc.io/repository/maven-snapshots/")
    }
    maven {
        name = "codemc-releases"
        url = uri("https://repo.codemc.io/repository/maven-releases/")
    }
    maven {
        name = "codemc-snapshots"
        url = uri("https://repo.codemc.io/repository/maven-snapshots/")
    }
    maven {
        name = "opencollab-releases"
        url = uri("https://repo.opencollab.dev/maven-releases/")
    }
    maven {
        name = "opencollab-snapshots"
        url = uri("https://repo.opencollab.dev/maven-snapshots/")
    }
}

dependencies {
    // Paper API
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    
    // Kotlin stdlib
    implementation(kotlin("stdlib"))
    
    // Caffeine cache for high-performance caching
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    
    // PacketEvents
    compileOnly("com.github.retrooper:packetevents-spigot:2.10.1")
    
    // Geyser/Floodgate (optional soft dependencies)
    compileOnly("org.geysermc.geyser:api:2.4.2-SNAPSHOT") {
        isTransitive = false
    }
    compileOnly("org.geysermc.floodgate:api:2.2.3-SNAPSHOT") {
        isTransitive = false
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    
    processResources {
        val props = mapOf(
            "version" to version,
            "name" to "ProjectAntiFreeCam"
        )
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    
    shadowJar {
        archiveBaseName.set("ProjectAntiFreeCam")
        archiveClassifier.set("")
        archiveVersion.set(version.toString())
        
        // Don't relocate Kotlin - just bundle it
        // This avoids ASM issues with Kotlin metadata
        mergeServiceFiles()
    }
    
    // Disable the default jar task (we only want shadowJar)
    jar {
        enabled = false
    }
    
    build {
        dependsOn(shadowJar)
    }
}
