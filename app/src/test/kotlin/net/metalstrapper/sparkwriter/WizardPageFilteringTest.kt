package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import org.junit.Assert.assertEquals
import org.junit.Test

class WizardPageFilteringTest {
    private val runtime = ManifestRuntime()

    @Test
    fun skipsWizardPageWithOnlyInfoFields() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "config_fields": [
                {"id": "headline", "label": "Headline", "type": "info", "default": "Hello"},
                {"id": "hostname", "label": "Hostname"}
              ],
              "wizard": {
                "pages": [
                  {"id": "intro", "title": "Intro", "fields": ["headline"]},
                  {"id": "identity", "title": "Identity", "fields": ["hostname"]}
                ]
              }
            }
            """.trimIndent(),
        )

        val pageIds = manifest.displayWizardPages(manifest.configFields).map { it.id }

        assertEquals(listOf("identity"), pageIds)
    }

    @Test
    fun skipsWizardPageWithOnlyDisabledOrImmutableFields() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "config_fields": [
                {"id": "serial", "label": "Serial", "disabled": true, "default": "abc"},
                {"id": "token", "label": "Token", "immutable": true, "default": "123"},
                {"id": "hostname", "label": "Hostname"}
              ],
              "wizard": {
                "pages": [
                  {"id": "locked", "title": "Locked", "fields": ["serial", "token"]},
                  {"id": "identity", "title": "Identity", "fields": ["hostname"]}
                ]
              }
            }
            """.trimIndent(),
        )

        val pageIds = manifest.displayWizardPages(manifest.configFields).map { it.id }

        assertEquals(listOf("identity"), pageIds)
    }

    @Test
    fun keepsWizardPageWhenAtLeastOneFieldIsEditable() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "config_fields": [
                {"id": "serial", "label": "Serial", "disabled": true, "default": "abc"},
                {"id": "hostname", "label": "Hostname"}
              ],
              "wizard": {
                "pages": [
                  {"id": "identity", "title": "Identity", "fields": ["serial", "hostname"]}
                ]
              }
            }
            """.trimIndent(),
        )

        val pages = manifest.displayWizardPages(manifest.configFields)

        assertEquals(listOf("identity"), pages.map { it.id })
        assertEquals(listOf("serial", "hostname"), pages.single().fields.map { it.id })
    }

    @Test
    fun skipsDeclaredPageWhenRemainingFieldsAreReviewOnly() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "config_fields": [
                {"id": "hostname", "label": "Hostname"},
                {"id": "summary", "label": "Summary", "type": "info", "default": "Done"},
                {"id": "serial", "label": "Serial", "disabled": true, "default": "abc"}
              ],
              "wizard": {
                "pages": [
                  {"id": "identity", "title": "Identity", "fields": ["hostname"]},
                  {"id": "review-only", "title": "Details", "fields": ["summary", "serial"]}
                ]
              }
            }
            """.trimIndent(),
        )

        val pageIds = manifest.displayWizardPages(manifest.configFields).map { it.id }

        assertEquals(listOf("identity"), pageIds)
    }

    @Test
    fun requiresReviewStepWhenManifestHasReturnDelivery() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "host_events": [
                {
                  "event": "write_complete",
                  "return_delivery": {
                    "url": "https://returns.example/sparkwriter",
                    "delivery_mode": "required",
                    "template": "receipt"
                  }
                }
              ],
              "templates": {
                "receipt": "{\"status\":\"{{status}}\"}"
              }
            }
            """.trimIndent(),
        )

        assertEquals(true, manifest.requiresReviewStep(emptyList()))
    }

    @Test
    fun hiddenFieldsDoNotPreventReturnDeliveryReviewStep() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "config_fields": [
                {"id": "secret_endpoint_hint", "label": "Hint", "hidden": true, "default": "not shown"}
              ],
              "host_events": [
                {
                  "event": "write_complete",
                  "return_delivery": {
                    "url": "https://returns.example/sparkwriter",
                    "content_var": "generated_secret"
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val displayFields = manifest.configFields.filterNot { it.hidden }

        assertEquals(true, manifest.requiresReviewStep(displayFields))
    }
}
