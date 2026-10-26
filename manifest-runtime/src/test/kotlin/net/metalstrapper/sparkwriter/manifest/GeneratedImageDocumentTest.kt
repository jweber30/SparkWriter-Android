package net.metalstrapper.sparkwriter.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks an image document produced by a MetalStrap build (tools/metalstrap.py)
 * against every bundled and example workflow that names it. Runs only when
 * SPARKWRITER_IMAGE_DOC points at the generated file, as in the MetalStrap
 * release workflow.
 */
class GeneratedImageDocumentTest {
    private val runtime = ManifestRuntime()
    private val repoRoot = listOf(File("."), File("..")).first { File(it, "app/bundled").isDirectory }

    @Test
    fun generatedImageWorksWithEveryWorkflowThatNamesIt() {
        val path = System.getenv("SPARKWRITER_IMAGE_DOC")
        assumeTrue("Set SPARKWRITER_IMAGE_DOC to check a generated image document", !path.isNullOrBlank())
        val file = File(path!!).let { if (it.isAbsolute) it else File(repoRoot, path) }
        val image = file.readText()
        val imageId = runtime.imageDocumentId(image)

        val workflows = listOf("app/bundled", "examples")
            .flatMap { File(repoRoot, it).listFiles().orEmpty().filter { file -> file.name.endsWith(".sparkplug.yaml") } }
            .filter { runtime.workflowImageId(it.readText()) == imageId }
        assertTrue("No bundled or example workflow uses image $imageId", workflows.isNotEmpty())

        workflows.forEach { workflow ->
            val manifest = runtime.parse(workflow.readText(), image)
            assertEquals("${workflow.name} unavailable with the generated image", emptyList<String>(), manifest.unsupportedReasons)
            assertEquals(manifest.source?.sha256, manifest.delivery?.baseImageSha256)
        }
    }
}
