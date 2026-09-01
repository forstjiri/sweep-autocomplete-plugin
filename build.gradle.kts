import com.google.protobuf.gradle.id
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.0"
    kotlin("jvm") version "2.1.0"
    id("com.google.protobuf") version "0.9.4"
    kotlin("plugin.serialization") version "1.9.20"
}

val pluginId = "dev.forstjiri.vulcansweep"
val pluginName = "Vulcan Sweep"
println("Building plugin: $pluginName with ID: $pluginId")
group = "dev.sweep"
version = "1.32.5"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

intellijPlatform {
    autoReload.set(false) // this triggers unloading which is very annoying
    buildSearchableOptions.set(false)
    pluginConfiguration {
        id.set(pluginId)
        name.set(pluginName)
    }

    pluginVerification {
        ides {
            // Avoid resolving a future IDE release that may not yet be
            // available from JetBrains archives.
            select {
                types.set(listOf(IntelliJPlatformType.IntellijIdeaCommunity))
                channels.set(listOf(ProductRelease.Channel.RELEASE))
                sinceBuild.set("251")
                untilBuild.set("252.*")
            }
        }
    }
}

tasks {
    // JDK 21 for IntelliJ Platform 2025.1+

    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
    }

    patchPluginXml {
        sinceBuild.set("251")
        untilBuild.set("262.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    processResources {
        // Set duplicate strategy for all files
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        // Ensure ripgrep binaries are included
        from("src/main/resources") {
            include("tools/**")
        }
    }

    processTestResources {
        // Set duplicate strategy for test resources
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    // Fix: Create a Copy task instead of DefaultTask for configuration cache compatibility
    val copyRipgrepToSandbox by creating(Copy::class) {
        val sandboxPluginDir =
            layout.buildDirectory
                .dir("idea-sandbox/plugins/${project.name}")

        from("src/main/resources/tools") {
            include("ripgrep/macos-aarch64/rg")
        }
        into(sandboxPluginDir.map { it.dir("tools") })

        doLast {
            println("Copied ripgrep to sandbox")
        }
    }

    // Hook the copy task to prepareSandbox
    prepareSandbox {
        finalizedBy(copyRipgrepToSandbox)
    }

    buildPlugin {
        archiveBaseName.set("sweepai")

        // Ensure the ripgrep binary is included in the plugin JAR
        from("src/main/resources") {
            include("tools/ripgrep/**")
            into("lib/tools")
        }
    }

    runIde {
        jvmArgs("-Xmx2g") // Set maximum heap size
        jvmArgs("-XX:ReservedCodeCacheSize=512m") // Set code cache size
        jvmArgs("-XX:+UseG1GC") // Use G1 garbage collector
        jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=50") // Soft references policy
        jvmArgs("-ea") // Enable assertions
        jvmArgs("-Djava.net.preferIPv4Stack=true") // Prefer IPv4
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")
        jvmArgs(
            "-XX:StartFlightRecording=filename=${project.layout.buildDirectory.get().asFile}/pluginPerf.jfr,settings=profile,duration=60s,maxsize=1g,dumponexit=true",
        )
    }

    task<Exec>("e2e") {
        commandLine("./bin/e2e")
    }

    task<Exec>("format") {
        group = "format"
        commandLine("./bin/format")
    }

    task<Exec>("release") {
        group = "plugin"
        commandLine("./bin/release")
    }

    task<Exec>("installPlugin") {
        group = "plugin"
        commandLine("./bin/install")
    }

    task<Exec>("installCloudPlugin") {
        group = "plugin"
        commandLine("./bin/install", "--cloud")
    }

    task<Exec>("uninstallPlugin") {
        group = "plugin"
        commandLine("./bin/uninstall")
    }

    test {
        useJUnitPlatform()
        systemProperty("idea.force.use.core.classloader", "true")
        systemProperty("idea.use.core.classloader.for.plugin.path", "true")
    }

    verifyPlugin {}
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r")
    implementation("com.google.protobuf:protobuf-kotlin:3.23.4")
    implementation("com.aayushatharva.brotli4j:brotli4j:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-osx-aarch64:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-osx-x86_64:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-linux-x86_64:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-linux-aarch64:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-windows-x86_64:1.16.0")
    implementation("com.aayushatharva.brotli4j:native-windows-aarch64:1.16.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("io.kotest:kotest-assertions-core:5.6.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    intellijPlatform {
        // https://www.jetbrains.com/idea/download/other.html
//        androidStudio("2024.3.2.11") // Android Studio Meerkat | 2024.3.2 Patch 11
//        androidStudio("2025.1.1.11") // Android Studio Narwhal | 2025.1.1 Patch 11
        intellijIdeaCommunity("2025.1")
//        rustRover("2025.1")
//        intellijIdeaCommunity("2023.3.8")
//        intellijIdeaCommunity("2023.1.7")
//        intellijIdeaCommunity("2023.1.2")
//        intellijIdeaUltimate("2025.1")
//        pycharmCommunity("2024.2.4")
//        pycharmCommunity("2024.3.4")
//        pycharmCommunity("2025.1.1")
//        pycharmProfessional("2025.1")
        bundledPlugins("org.jetbrains.plugins.terminal", "org.intellij.plugins.markdown", "Git4Idea")
//        plugin("IdeaVIM:2.19.0")
//        plugin("org.jetbrains.completion.full.line:241.18034.76") // requires  intellijIdeaUltimate("2024.1.7")
//        plugin("com.github.copilot:1.5.45-243") // requires kotlin 2.1.0 and a lot of memory
        testFramework(TestFrameworkType.Platform)
    }
}

configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "org.slf4j", module = "slf4j-simple")
    exclude(group = "org.slf4j", module = "slf4j-log4j12")
    exclude(group = "ch.qos.logback", module = "logback-classic")
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDirs(
                "src/main/kotlin",
                "build/generated/source/proto/main/java",
                "build/generated/source/proto/main/kotlin",
                "scripts",
            )
            // Add resources directory explicitly
            resources.srcDirs("src/main/resources")
        }
        test {
            kotlin.srcDirs("src/test/kotlin")
            resources.srcDirs("src/test/resources")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.23.4"
    }

    // Generate Kotlin code
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("kotlin")
            }
        }
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                args("${project.projectDir}")
                jvmArgumentProviders +=
                    CommandLineArgumentProvider {
                        listOf(
                            "-Drobot-server.port=8082",
                            "-Didea.trust.all.projects=true",
                            "-Dide.mac.message.dialogs.as.sheets=false",
                            "-Djb.privacy.policy.text=<!--999.999-->",
                            "-Djb.consents.confirmation.enabled=false",
                            "-Dide.show.tips.on.startup.default.value=false",
                        )
                    }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}

// Task to run the llama.cpp test
tasks.register<Test>("runLlamaCppTest") {
    group = "verification"
    description = "Run the llama.cpp integration test"

    useJUnitPlatform()
    filter {
        includeTestsMatching("dev.sweep.assistant.autocomplete.llamacpp.LlamaCppTest")
    }

    // Enable the test
    systemProperty("llama.test.enabled", "true")

    // Optional: override model path
    // systemProperty("llama.model.path", "/path/to/your/model.gguf")

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }
}
