plugins {
    id("java")
    kotlin("jvm") version "1.9.0"
    kotlin("plugin.serialization") version "1.9.0"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    // kotlinx-serialization is the only runtime dep we keep; everything else is in the platform.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}

intellij {
    version.set(providers.gradleProperty("platformVersion"))
    type.set(providers.gradleProperty("platformType"))

    // The terminal plugin gives us TerminalToolWindowManager for embedded shell tabs.
    plugins.set(listOf("org.jetbrains.plugins.terminal"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// The plugin reports its own version to agy over MCP (serverInfo.version). Reading it back
// from the platform at runtime is not an option: every API that resolves a plugin descriptor
// (PluginManagerCore.getPlugin, PluginManager.getPluginByClass) is marked @ApiStatus.Internal,
// and the Marketplace verifier rejects those. So bake the version into a resource at build
// time instead — one source of truth (gradle.properties), no platform API, and it cannot drift
// the way a hardcoded constant did.
val generateVersionProperties by tasks.registering {
    val pluginVersion = providers.gradleProperty("pluginVersion")
    val outputDir = layout.buildDirectory.dir("generated/version-resource")
    inputs.property("pluginVersion", pluginVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("META-INF/antigravity-companion-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=${pluginVersion.get()}\n")
    }
}

sourceSets {
    main {
        resources.srcDir(generateVersionProperties)
    }
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("262.*")
        // Description and change-notes live in plugin.xml so they ship versioned with the source.
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }
}
