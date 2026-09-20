package uz.dukeengine.plugin.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What File → New → Duke Game writes.
 *
 * <p>Worth pinning because nobody reads a generated project before it is generated: the first thing a new user
 * sees is this, and a manifest that lists a file the wizard did not write is a game that will not start.
 */
class GameTemplateTest {

    @Test
    fun `the manifest also lists a couple of the kit's effects, so a new game has some`() {
        val manifest = GameTemplate.of("Siege", "com.example.siege", null)
            .getValue("src/main/resources/data/game.duke")
        assertTrue(manifest, manifest.contains("kit/data/effects/"))
        val scout = GameTemplate.of("Siege", "com.example.siege", null)
            .getValue("src/main/resources/data/units/scout.duke")
        assertTrue("and a unit wears one of them", scout.contains("Effect = Focus"))
    }

    @Test
    fun `the manifest lists exactly the data files that were written`() {
        val made = GameTemplate.of("My Duke Game", "com.example.mydukegame", null)

        // Anchored to the start of a line, because the manifest also lists the kit's files — whose paths
        // hold "data/" too, and which are not this project's to write.
        val listed = Regex("""^\s*(data/[\w/.-]+\.duke),""", RegexOption.MULTILINE)
            .findAll(made.getValue("src/main/resources/data/game.duke")).map { it.groupValues[1] }.toSet()
        val written = made.keys.filter { it.startsWith("src/main/resources/data/") }
            .map { it.removePrefix("src/main/resources/") }.toSet() - "data/game.duke"

        assertEquals("the manifest and the folder should say the same thing", written, listed)
        assertTrue("and there should be something in it", listed.isNotEmpty())
    }

    @Test
    fun `the two classes sit in the package the build names`() {
        val made = GameTemplate.of("Siege", "com.example.siege", null)

        assertTrue(made.containsKey("src/main/java/com/example/siege/Main.java"))
        assertTrue(made.containsKey("src/main/java/com/example/siege/Content.java"))
        assertTrue(
            "a generated game comes with a test of its own, so the first thing it learns is that it can be tested",
            made.containsKey("src/test/java/com/example/siege/GameLoadsTest.java"),
        )
        assertTrue(made.getValue("src/main/java/com/example/siege/Main.java").startsWith("package com.example.siege;"))
        assertTrue(
            "the build must point at the Main that was written",
            made.getValue("build.gradle.kts").contains("mainClass.set(\"com.example.siege.Main\")"),
        )
        val build = made.getValue("build.gradle.kts")
        assertTrue("the version is written once, in the platform", build.contains("platform(\"uz.duke-engine:bom:"))
        assertFalse("and nowhere else", build.contains("uz.duke-engine:client3d:"))
    }

    @Test
    fun `the engine is built alongside when a checkout is given, and not when it is not`() {
        val alongside = GameTemplate.of("Siege", "com.example.siege", "C:\\work\\duke-engine")
        assertTrue(
            alongside.getValue("settings.gradle.kts").contains("includeBuild(\"C:/work/duke-engine\")"),
        )

        val alone = GameTemplate.of("Siege", "com.example.siege", null)
        assertFalse(alone.getValue("settings.gradle.kts").contains("includeBuild"))
        assertTrue(alone.getValue("settings.gradle.kts").contains("rootProject.name = \"siege\""))
    }

    /** The generated game is meant to run, so it must spawn what its own files declare. */
    @Test
    fun `everything Main spawns is a unit the files declare`() {
        val made = GameTemplate.of("Siege", "com.example.siege", null)
        val declared = made.filterKeys { it.startsWith("src/main/resources/data/units/") }
            .values.flatMap { Regex("""Name = (\w+)""").findAll(it).map { hit -> hit.groupValues[1] } }.toSet()
        val spawned = Regex("""spawn\("(\w+)"""").findAll(made.getValue("src/main/java/com/example/siege/Main.java"))
            .map { it.groupValues[1] }.toSet()

        assertEquals("Main should spawn the units the data declares, and no others", declared, spawned)
    }

    @Test
    fun `a name becomes a folder and a package a compiler will take`() {
        assertEquals("my-first-game", GameTemplate.slug("  My First   Game! "))
        assertEquals("duke-game", GameTemplate.slug("!!!"))
        assertEquals("com.example.myfirstgame", GameTemplate.packageOf("My First Game"))
        assertEquals("com.example.g2048", GameTemplate.packageOf("2048"))
    }
}
