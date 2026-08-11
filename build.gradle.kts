
plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Local dev builds against the installed PyCharm CE app (see gradle.properties)
        // to avoid a ~1GB SDK download on this 8GB machine. CI has no local install, so
        // it falls back to the standard downloadable artifact instead.
        if (providers.environmentVariable("CI").isPresent) {
            // The marketing version string ("2025.2.5") fails to resolve a download
            // URL via this helper; the exact build number does.
            pycharmCommunity("252.28238.29")
        } else {
            local(providers.gradleProperty("platformLocalPath"))
        }
        bundledPlugin("PythonCore")
    }

    // The platform ships both of these; compiling against them must not bundle them.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test"))
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // The IntelliJ Platform jars land on the test classpath and reference JUnit 4
    // types at class-load time, even though our own tests are JUnit 5 only.
    testRuntimeOnly("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // The IDE ships the Kotlin 2.1 stdlib; don't emit metadata it can't read.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// Launches a whole IDE to index the settings UI for the Settings search box.
// Not worth a minute of every build, and it cannot run while a sandbox IDE is
// open ("Only one instance of PyCharm can be run at a time").
tasks.buildSearchableOptions {
    enabled = false
}

// `./gradlew runIde -PrunIdeProject=/path/to/project` opens that project in the
// sandbox IDE, so manual phase verification doesn't start from the welcome screen.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    val projectPath = providers.gradleProperty("runIdeProject")
    argumentProviders.add(CommandLineArgumentProvider {
        projectPath.orNull?.let { listOf(it) } ?: emptyList()
    })
}
