import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask

plugins {
    kotlin("jvm") version ("2.1.0")
    id("architectury-plugin") version "3.4.162"
    id("dev.architectury.loom") version "1.13-SNAPSHOT" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

architectury {
    minecraft = project.properties["minecraft_version"]!! as String
}

subprojects {
    apply(plugin = "dev.architectury.loom")
    dependencies {
        "minecraft"("com.mojang:minecraft:${project.properties["minecraft_version"]!!}")
        "mappings"("net.fabricmc:yarn:${project.properties["yarn_mappings"]}")
    }
    if (path != ":common") {
        apply(plugin = "com.github.johnrengelman.shadow")

        val shadowCommon by configurations.creating {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        val versionWithMCVersion = "${project.properties["mod_version"]!!}+${project.properties["minecraft_version"]!!}"

        tasks.withType<JavaCompile> {
            options.encoding = "UTF-8"
            options.release = 21
        }

        tasks {
            val shadowJarTask = named("shadowJar", ShadowJar::class)
            shadowJarTask {
                archiveVersion = versionWithMCVersion
                archiveClassifier.set("shadow")
                configurations = listOf(shadowCommon)
            }

            "remapJar"(RemapJarTask::class) {
                dependsOn(shadowJarTask)
                inputFile = shadowJarTask.flatMap { it.archiveFile }
                archiveVersion = versionWithMCVersion
                archiveClassifier = ""
            }
            jar {
                enabled = false
            }
        }
    }
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "architectury-plugin")
    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        maven { url = uri("https://jitpack.io") }
        maven {
            name = "CottonMC"
            url = uri("https://server.bbkr.space/artifactory/libs-release")
        }
        maven { url = uri("https://maven.shedaniel.me/") }
        maven { url = uri("https://maven.terraformersmc.com/releases/") }
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.architectury.dev/") }
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "Modrinth"
                    url = uri("https://api.modrinth.com/maven")
                }
            }
            filter {
                includeGroup("maven.modrinth")
            }
        }
    }

    base.archivesName.set(project.properties["archives_base_name"]!! as String)
    group = project.properties["maven_group"]!!
    version = project.properties["mod_version"]!!



    tasks {
        compileKotlin {
            kotlinOptions.jvmTarget = "21"
        }
    }

    tasks.withType(JavaCompile::class.java) {
        options.encoding = "UTF-8"
        options.release = 21
    }
}
