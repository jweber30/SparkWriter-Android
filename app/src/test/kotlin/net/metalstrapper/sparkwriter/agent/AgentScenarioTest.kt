package net.metalstrapper.sparkwriter.agent

import net.metalstrapper.sparkwriter.activeConfigFields
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.displayWizardPages
import net.metalstrapper.sparkwriter.draftLandingStep
import net.metalstrapper.sparkwriter.fixtures.parseWorkflowFile
import net.metalstrapper.sparkwriter.formFieldId
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import net.metalstrapper.sparkwriter.validateDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Keeps tools/agent-test/scenarios honest. Each scenario pairs a request in plain
 * words with the values an assistant should send. If a SparkPlug changes so that
 * an expected value is refused, or the draft would open somewhere else, this
 * fails, so the scenarios stay usable for checking real assistants.
 */
class AgentScenarioTest {
    private val runtime = ManifestRuntime()
    private val root = listOf(File("."), File("..")).first { File(it, "tools/agent-test/scenarios").isDirectory }
    private val bundled = File(root, "app/bundled")

    private fun plug(id: String): SparkManifest = bundled.listFiles().orEmpty()
        .filter { it.name.endsWith(".sparkplug.yaml") }
        .map { runtime.parseWorkflowFile(it) }
        .single { it.metadata.id == id }

    private fun scenarios(): Map<String, Properties> = File(root, "tools/agent-test/scenarios")
        .listFiles { file -> file.name.endsWith(".properties") }.orEmpty()
        .sortedBy { it.name }
        .associate { file -> file.name to Properties().apply { file.reader().use(::load) } }

    @Test
    fun scenariosExist() {
        assertTrue(scenarios().size >= 3)
    }

    @Test
    fun everyExpectedValueIsAcceptedAndLandsWhereStated() {
        scenarios().forEach { (name, scenario) ->
            val manifest = plug(scenario.getProperty("plug"))
            val expected = scenario.stringPropertyNames()
                .filter { it.startsWith("expect.") }
                .associate { it.removePrefix("expect.") to scenario.getProperty(it) }
            assertTrue("$name has no prompt", scenario.getProperty("prompt").orEmpty().isNotBlank())

            val validation = manifest.validateDraft(expected)
            assertEquals("$name: refused values", emptyList<Any>(), validation.issues)

            assertEquals("$name: landing step", scenario.getProperty("lands"), manifest.landingName(validation.accepted))
        }
    }

    /** "review", or the title of the wizard page the draft opens on. */
    private fun SparkManifest.landingName(accepted: Map<String, String>): String {
        val values = configFields.filter { it.default.isNotEmpty() }.associate { it.formFieldId() to it.default } + accepted
        val pages = displayWizardPages(activeConfigFields(values).filterNot { it.hidden })
        val step = draftLandingStep(values)
        return pages.getOrNull(step)?.title ?: "review"
    }
}
