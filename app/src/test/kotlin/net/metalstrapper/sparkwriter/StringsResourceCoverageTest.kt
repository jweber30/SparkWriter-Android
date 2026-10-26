package net.metalstrapper.sparkwriter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringsResourceCoverageTest {
    @Test
    fun allLocalizedStringResourcesMatchDefaultKeys() {
        val resDir = resolveResDirectory()
        val baseKeys = readStringKeys(resDir.resolve("values").resolve("strings.xml"))

        val localeDirs = resDir
            .listFiles { _, name -> name.startsWith("values-") }
            // Qualifier-only folders such as values-night carry no translations.
            ?.filter { it.resolve("strings.xml").isFile }
            ?.sortedBy { it.name }
            ?: emptyList()

        for (localeDir in localeDirs) {
            val localeKeys = readStringKeys(localeDir.resolve("strings.xml"))
            val missing = (baseKeys - localeKeys).sorted()
            val extra = (localeKeys - baseKeys).sorted()

            assertEquals(
                "Locale ${localeDir.name} should match base keys. missing=${missing}, extra=${extra}",
                baseKeys,
                localeKeys,
            )
        }
    }

    private fun resolveResDirectory(): File {
        val candidate = listOf(
            File("app/src/main/res"),
            File("src/main/res"),
        ).firstOrNull { it.exists() && it.isDirectory }

        return candidate ?: error("Could not find Android main resources directory")
    }

    private fun readStringKeys(file: File): Set<String> {
        val document = DocumentBuilderFactory
            .newInstance()
            .apply {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            .newDocumentBuilder()
            .parse(file)
            .documentElement

        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it).attributes.getNamedItem("name").nodeValue }
            .toSet()
    }
}
