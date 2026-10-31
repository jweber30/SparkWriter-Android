package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.SharedText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SharedTextResourcesTest {
    @Test
    fun everySharedTextIdHasAnAndroidString() {
        assertEquals(SharedText.ids, SharedTextResources.keys)
    }

    @Test
    fun defaultStringsMatchSharedTextEnglish() {
        val strings = File(listOf("app/src/main/res", "src/main/res").first { File(it).isDirectory }, "values/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings).documentElement
        val nodes = document.getElementsByTagName("string")
        val byName = (0 until nodes.length).associate {
            nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
        }

        SharedText.english.forEach { (id, english) ->
            assertEquals(id, english, byName["shared_text_" + id.replace('.', '_')])
        }
    }
}
