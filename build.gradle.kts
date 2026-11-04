// One jar per Minecraft version. Pick the version with -Pmc=<version>, e.g. ./gradlew build -Pmc=26.3
// Each version's dependencies are in versions/<version>.properties.
//
// Build with JDK 25 or newer, it compiles for each version's Java release.
//
// 1.21.x uses Yarn names and is remapped (sources in src/mc1.21), 26.x is unobfuscated and uses Mojang names (sources
// in src/mc26). The few calls that differ between versions of the same family are in src/compat/<compat>/Compat.java.

import java.util.Properties

plugins {
    java
    id("net.fabricmc.fabric-loom-remap") version libs.versions.loom apply false
    id("net.fabricmc.fabric-loom") version libs.versions.loom apply false
}

val mcVersion = providers.gradleProperty("mc").get()
val versionFile = file("versions/$mcVersion.properties")
require(versionFile.exists()) {
    "No versions/$mcVersion.properties, supported: " + file("versions").list()!!.map { it.removeSuffix(".properties") }.sorted().joinToString()
}
val mc = Properties().apply { versionFile.reader().use { load(it) } }
fun mc(key: String): String = mc.getProperty(key) ?: error("versions/$mcVersion.properties has no $key")

val remapped = mc.getProperty("yarn") != null
val javaVersion = mc("java").toInt()

apply(plugin = if (remapped) "net.fabricmc.fabric-loom-remap" else "net.fabricmc.fabric-loom")

base {
    archivesName = "${providers.gradleProperty("archives_base_name").get()}-mc$mcVersion"
    version = libs.versions.mod.version.get()
    group = providers.gradleProperty("maven_group").get()
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src/${mc("source")}/java", "src/compat/${mc("compat")}/java"))
    }
}

dependencies {
    // Fabric
    "minecraft"("com.mojang:minecraft:${mc("minecraft")}")
    if (remapped) {
        "mappings"("net.fabricmc:yarn:${mc("yarn")}:v2")
        "modImplementation"("net.fabricmc:fabric-loader:${mc("fabric_loader")}")
        "modImplementation"("meteordevelopment:meteor-client:${mc("meteor")}")
    }
    else {
        "implementation"("net.fabricmc:fabric-loader:${mc("fabric_loader")}")
        "implementation"("meteordevelopment:meteor-client:${mc("meteor")}")
    }
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to mc("minecraft"),
            "java_version" to javaVersion
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        val archivesName = base.archivesName.get()
        inputs.property("archivesName", archivesName)

        from("LICENSE") {
            rename { "${it}_$archivesName" }
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = javaVersion
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
