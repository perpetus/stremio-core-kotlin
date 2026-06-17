import com.google.protobuf.gradle.*

group = "com.github.Stremio"
version = "1.15.0"

allprojects {
  repositories {
    google()
    mavenCentral()
  }
}

plugins {
  kotlin("multiplatform") version "1.9.25"
  id("com.google.protobuf") version "0.9.4"
  id("com.android.library") version "9.2.0"
  id("maven-publish")
}

val kotlinVersion: String by extra
val pbandkVersion: String by extra
val protobufVersion: String by extra

buildscript {
  extra["kotlinVersion"] = "1.9.25"
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
  androidTarget {
    // TODO: Adding a "debug" variant here results in failing imports in KMM projects. Figure out why.
    publishLibraryVariants("release")
  }

  @Suppress("UNUSED_VARIABLE")
  sourceSets {
    val commonMain by getting {
      kotlin.srcDir("build/generated/source/proto/release/pbandk")
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

android {
  ndkVersion = "29.0.13846066" // configure in .cargo/config.toml and workflows/release.yml as well

  defaultConfig {
    namespace = "com.stremio.core"
    minSdk = 21
    compileSdk = 34
  }

  sourceSets {
    getByName("main") {
      proto {
        srcDirs("../stremio-core-protobuf/proto")
      }
      manifest.srcFile("src/androidMain/AndroidManifest.xml")
    }
  }

  packaging {
    resources {
      excludes += "**/*.proto"
    }
  }

  variantFilter {
    if (name == "debug") {
      ignore = true
    }
  }
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:${protobufVersion}"
  }

  plugins {
    id("pbandk") {
      artifact = "pro.streem.pbandk:protoc-gen-pbandk-jvm:${pbandkVersion}:jvm8@jar"
    }
  }

  generateProtoTasks {
    all().forEach { task ->
      task.plugins {
        id("pbandk")
      }
    }
  }
}

val cargoReleaseBuild = true

val cargoTargets = mapOf(
    "arm64" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android"
)

val targetJniMap = mapOf(
    "arm64" to "arm64-v8a",
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

tasks.named("preBuild") {
    dependsOn(copyJniLibs)
}

// No manual copy tasks needed. Protobuf output dir is mapped dynamically above.

afterEvaluate {
}
