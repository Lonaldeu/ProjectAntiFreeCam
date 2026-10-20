plugins {
    java
}

group = "me.lonaldeu"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.10.1")
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to version) }
}
