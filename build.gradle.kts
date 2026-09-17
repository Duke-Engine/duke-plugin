import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// The 2025.3 platform runs on Java 21; building with a newer JAVA_HOME would emit classes it cannot load.
kotlin {
    jvmToolchain(21)
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)

        // Module names are checked against the engine's Java classes.
        bundledPlugin("com.intellij.java")
    }
}

tasks.test {
    // The IDE bundles plugins (Vue among them) that cannot start from the test classpath and fail
    // any test that completes or renames; load only this plugin and what it depends on.
    systemProperty("idea.load.plugins.id", "uz.duke.plugin")
}
