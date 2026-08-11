import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
        local(providers.gradleProperty("platformLocalPath"))
        bundledPlugin("PythonCore")
        testFramework(TestFrameworkType.Platform)
    }

    // The platform ships both of these; compiling against them must not bundle them.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test"))
    testImplementation("com.google.code.gson:gson:2.11.0")
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

// `./gradlew runIde -PrunIdeProject=/path/to/project` opens that project in the
// sandbox IDE, so manual phase verification doesn't start from the welcome screen.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    val projectPath = providers.gradleProperty("runIdeProject")
    argumentProviders.add(CommandLineArgumentProvider {
        projectPath.orNull?.let { listOf(it) } ?: emptyList()
    })
}
