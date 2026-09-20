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
    systemProperty("idea.load.plugins.id", "uz.dukeengine.plugin")
}

// ---------------------------------------------------------------------------
// The release.
//
//   ./gradlew buildPlugin         # the zip, in build/distributions
//   ./gradlew verifyPlugin        # what JetBrains checks before it takes one
//   ./gradlew publishPlugin       # needs the token and the signing key below
//
// Nothing secret is in this repository. The release workflow passes them as
// environment variables, and they can be set by hand to publish from a laptop:
//
//   PUBLISH_TOKEN          from plugins.jetbrains.com, under Your Profile
//   CERTIFICATE_CHAIN      the plugin signing chain, as PEM
//   PRIVATE_KEY            its private key, as PEM
//   PRIVATE_KEY_PASSWORD   the key's passphrase
//
// See https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
// ---------------------------------------------------------------------------
intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()

        ideaVersion {
            // 2025.3, the platform this is built and tested against.
            sinceBuild = "253"
            // No upper bound: a version written here is a version the plugin refuses to install on before
            // anybody has found out whether it would have worked. `verifyPlugin` is what says whether it does,
            // and it is run on every release.
            untilBuild = provider { null }
        }

        // What changed, taken from CHANGELOG.md rather than written twice. `./gradlew patchChangelog` moves
        // the Unreleased section under the version being released.
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    org.jetbrains.changelog.Changelog.OutputType.HTML,
                )
            }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A 0.x goes to the beta channel: somebody who wants it adds the channel's URL on purpose, and
        // nobody gets it by default until the version has no `0.` in front of it.
        channels = providers.gradleProperty("version").map {
            listOf(if (it.startsWith("0.")) "beta" else "default")
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}
