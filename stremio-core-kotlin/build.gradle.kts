import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

group = "com.github.Stremio"
version = "1.15.0"

allprojects {
  repositories {
    google()
    mavenCentral()
  }
}

plugins {
  kotlin("multiplatform") version "2.4.0"
  id("com.android.kotlin.multiplatform.library") version "9.2.0"
  id("maven-publish")
}

val kotlinVersion: String by extra
val pbandkVersion: String by extra
val protobufVersion: String by extra

buildscript {
  extra["kotlinVersion"] = "2.4.0"
  extra["pbandkVersion"] = "0.16.0"
  extra["protobufVersion"] = "4.28.3"

  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
  }
  android {
    namespace = "com.stremio.core"
    minSdk = 21
    compileSdk = 34
    withJava()
    withHostTestBuilder {}.configure {}
  }

  @Suppress("UNUSED_VARIABLE")
  sourceSets {
    val commonMain by getting {
      kotlin.srcDir("build/generated/source/proto/main/pbandk")
      dependencies {
        implementation("pro.streem.pbandk:pbandk-runtime:${pbandkVersion}")
      }
    }
    val androidMain by getting {
      dependencies {
        implementation("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")
      }
    }
  }
}

fun protocClassifier(): String {
  val os = System.getProperty("os.name").lowercase()
  val arch = System.getProperty("os.arch").lowercase()
  return when {
    os.contains("windows") -> "windows-x86_64"
    os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "osx-aarch_64"
    os.contains("mac") -> "osx-x86_64"
    arch.contains("aarch64") || arch.contains("arm64") -> "linux-aarch_64"
    else -> "linux-x86_64"
  }
}

val protocExecutableConfiguration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

val pbandkGeneratorConfiguration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

val protobufWellKnownTypesConfiguration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies {
  protocExecutableConfiguration("com.google.protobuf:protoc:${protobufVersion}:${protocClassifier()}@exe")
  pbandkGeneratorConfiguration("pro.streem.pbandk:protoc-gen-pbandk-jvm:${pbandkVersion}:jvm8@jar")
  protobufWellKnownTypesConfiguration("com.google.protobuf:protobuf-java:${protobufVersion}")
}

val extractedProtoIncludes = layout.buildDirectory.dir("extracted/protobuf/includes")
val protoSourceRoot = layout.projectDirectory.dir("../stremio-core-protobuf/proto")
val generatedProtoKotlin = layout.buildDirectory.dir("generated/source/proto/main/pbandk")

abstract class GeneratePbandkProto @Inject constructor(
  private val execOperations: ExecOperations,
) : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val protoRoot: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val protoFiles: ConfigurableFileCollection

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val includeDirectory: DirectoryProperty

  @get:Classpath
  abstract val protocExecutable: ConfigurableFileCollection

  @get:Classpath
  abstract val pbandkGenerator: ConfigurableFileCollection

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @get:OutputDirectory
  abstract val wrapperDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val outputDir = outputDirectory.get().asFile
    val wrapperDir = wrapperDirectory.get().asFile
    val protoc = protocExecutable.singleFile
    val pbandkJar = pbandkGenerator.singleFile
    val includeDir = includeDirectory.get().asFile

    outputDir.deleteRecursively()
    outputDir.mkdirs()
    wrapperDir.mkdirs()

    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val wrapper = if (isWindows) {
      wrapperDir.resolve("protoc-gen-pbandk.bat").apply {
        writeText("@echo off\r\njava -jar \"${pbandkJar.absolutePath}\" %*\r\n")
      }
    } else {
      wrapperDir.resolve("protoc-gen-pbandk").apply {
        writeText("#!/usr/bin/env sh\nexec java -jar \"${pbandkJar.absolutePath}\" \"$@\"\n")
        setExecutable(true)
      }
    }

    protoc.setExecutable(true)
    execOperations.exec {
      executable = protoc.absolutePath
      args(
        "--plugin=protoc-gen-pbandk=${wrapper.absolutePath}",
        "--pbandk_out=${outputDir.absolutePath}",
        "-I${protoRoot.get().asFile.absolutePath}",
        "-I${includeDir.absolutePath}",
      )
      args(protoFiles.files.map { it.absolutePath }.sorted())
    }

    outputDir
      .walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .forEach { file ->
        val text = file.readText()
        if (!text.startsWith("@file:Suppress(\"UNNECESSARY_NOT_NULL_ASSERTION\")")) {
          file.writeText("@file:Suppress(\"UNNECESSARY_NOT_NULL_ASSERTION\")\n\n$text")
        }
      }
  }
}

val extractProtoIncludes by tasks.registering(Copy::class) {
  from({ protobufWellKnownTypesConfiguration.map { zipTree(it) } })
  include("google/protobuf/**/*.proto")
  into(extractedProtoIncludes)
}

val generatePbandkProto by tasks.registering(GeneratePbandkProto::class) {
  dependsOn(extractProtoIncludes)

  val protoFiles = fileTree(protoSourceRoot) {
    include("**/*.proto")
  }

  this.protoRoot.set(protoSourceRoot)
  this.protoFiles.from(protoFiles)
  this.includeDirectory.set(extractedProtoIncludes)
  this.protocExecutable.from(protocExecutableConfiguration)
  this.pbandkGenerator.from(pbandkGeneratorConfiguration)
  this.outputDirectory.set(generatedProtoKotlin)
  this.wrapperDirectory.set(layout.buildDirectory.dir("protoc/plugin-wrapper"))
}

tasks.configureEach {
  if (name.contains("Kotlin", ignoreCase = true)) {
    dependsOn(generatePbandkProto)
  }
}

val cargoReleaseBuild = true

val cargoTargets = mapOf(
    "arm64" to "aarch64-linux-android",
    "armv7" to "armv7-linux-androideabi",
    "x86" to "i686-linux-android",
    "x86_64" to "x86_64-linux-android"
)

val targetJniMap = mapOf(
    "arm64" to "arm64-v8a",
    "armv7" to "armeabi-v7a",
    "x86" to "x86",
    "x86_64" to "x86_64"
)

cargoTargets.forEach { (name, targetTriple) ->
    tasks.register<Exec>("cargoBuild_${name}") {
        workingDir = file(".")
        val cmd = mutableListOf("cargo", "ndk", "--target", targetTriple, "--platform", "21", "build")
        if (cargoReleaseBuild) {
            cmd.add("--release")
        }
        if (org.apache.tools.ant.taskdefs.condition.Os.isFamily(org.apache.tools.ant.taskdefs.condition.Os.FAMILY_WINDOWS)) {
            commandLine("cmd", "/c", cmd.joinToString(" "))
        } else {
            commandLine(cmd)
        }
    }
}

val copyJniLibs = tasks.register<Copy>("copyJniLibs") {
    cargoTargets.keys.forEach { name ->
        dependsOn("cargoBuild_${name}")
        val profileDir = if (cargoReleaseBuild) "release" else "debug"
        from(file("../target/${cargoTargets[name]}/${profileDir}/libstremio_core_kotlin.so")) {
            into(targetJniMap[name]!!)
        }
    }
    into(file("src/androidMain/jniLibs"))
}

// Disabled automatic compilation to speed up builds.
// Run copyJniLibs task manually or compileNativeLibs from root to compile native libraries.
// tasks.named("preBuild") {
//     dependsOn(copyJniLibs)
// }

// No manual copy tasks needed. Protobuf output dir is mapped dynamically above.

afterEvaluate {
}
