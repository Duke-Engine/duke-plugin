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

// three.js draws the Inspector's preview: only the files the viewer imports are taken out of its webjar.
val three = configurations.create("three")

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)
    three("org.webjars.npm:three:0.181.1")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)

        // Module names are checked against the engine's Java classes.
        bundledPlugin("com.intellij.java")
    }
}

val viewerLibraries = tasks.register<Sync>("viewerLibraries") {
    from({ zipTree(three.singleFile) }) {
        val version = "META-INF/resources/webjars/three/*"
        include(
            "$version/LICENSE", "$version/build/three.module.js", "$version/build/three.core.js",
            "$version/examples/jsm/loaders/GLTFLoader.js", "$version/examples/jsm/utils/BufferGeometryUtils.js",
            "$version/examples/jsm/controls/OrbitControls.js", "$version/examples/jsm/utils/SkeletonUtils.js",
        )
        eachFile { relativePath = RelativePath(true, "viewer", "three", *relativePath.segments.drop(5).toTypedArray()) }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("generated/viewer"))
}

sourceSets.main { resources.srcDir(viewerLibraries) }

tasks.test {
    // The IDE bundles plugins (Vue among them) that cannot start from the test classpath and fail
    // any test that completes or renames; load only this plugin and what it depends on.
    systemProperty("idea.load.plugins.id", "uz.duke.plugin")
}
