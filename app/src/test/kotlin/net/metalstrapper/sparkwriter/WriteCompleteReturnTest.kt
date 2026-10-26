package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import kotlinx.coroutines.runBlocking
import net.metalstrapper.sparkwriter.core.BootKernelArgPatchSpec
import net.metalstrapper.sparkwriter.core.BootKernelArgSelector
import net.metalstrapper.sparkwriter.core.BootPatchPlan
import net.metalstrapper.sparkwriter.core.HostEventSubscription
import net.metalstrapper.sparkwriter.core.InlineText
import net.metalstrapper.sparkwriter.core.ReturnDeliverySpec
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.SparkPackageRoot
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WorkflowAction
import net.metalstrapper.sparkwriter.core.WriteIntent
import net.metalstrapper.sparkwriter.core.WriteReturnPlan
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import okio.Buffer

class WriteCompleteReturnTest {
    // These tests exercise external-service features, which the release build disables.
    private val runtime = ManifestRuntime(externalServicesEnabled = true)

    private var gateWasEnabled = false

    @Before
    fun enableExternalServices() {
        gateWasEnabled = ExternalServiceGate.enabled
        ExternalServiceGate.enabled = true
    }

    @After
    fun restoreExternalServices() {
        ExternalServiceGate.enabled = gateWasEnabled
    }
    private fun liveMailroomOrigin(): String =
        System.getenv("SPARKWRITER_LIVE_MAILROOM_ORIGIN")
            ?.trim()
            ?.trimEnd('/')
            ?.ifBlank { null }
            ?: "https://mailroom.metalstrapper.net"

    @Test
    fun serviceRenderedSeedPublishesAnswerAndFirstbootMailroomPuts() = runBlocking {
        val receivedPaths = Collections.synchronizedList(mutableListOf<String>())
        val deliveryErrors = Collections.synchronizedList(mutableListOf<Throwable>())
        try {
            val baseUrl = "https://mailroom-test.example"
            val manifest = runtime.parseCombined(
                """
                version: "3.0"
                metadata:
                  id: proxmox-mailroom-demo-test
                  name: Proxmox Mailroom Demo Test
                source:
                  id: proxmox-ve-ais-generic-netboot
                  name: Proxmox VE AIS generic netboot ISO
                  user_supplied: true
                  capabilities:
                    - netboot-rendezvous
                  adaptation:
                    status: patchable_on_import
                    satisfies:
                      - auto_install_mode
                      - answer_fetch.http
                installer:
                  scheme: proxmox-auto-install
                  accepts_delivery:
                    - mailroom
                  base_image_requires:
                    - auto_install_mode
                    - answer_fetch.http
                boot_patch:
                  filesystem: iso9660
                  placeholder_patches:
                    - candidate_paths:
                        - /auto-installer-mode.toml
                      placeholder: "$baseUrl/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
                      replacement: "{{Spark.mail.aux_data.answer_url}}"
                      encoding: ascii
                      padding: space
                templates:
                  answer:
                    - "[first-boot]"
                    - url = "{{Spark.mail.aux_data.firstboot_url}}"
                  firstboot:
                    - "#!/usr/bin/env bash"
                    - "echo ready"
                actions:
                  on_iso_ready:
                    - id: render-answer
                      type: render_template
                      template: answer
                      output_var: answer_toml
                    - id: create-answer
                      type: create_artifact
                      artifact_id: answer
                      media_type: text/plain
                      content_var: answer_toml
                    - id: render-firstboot
                      type: render_template
                      template: firstboot
                      output_var: firstboot_script
                    - id: create-firstboot
                      type: create_artifact
                      artifact_id: firstboot
                      media_type: text/plain
                      content_var: firstboot_script
                host_events:
                  - event: write_ready
                    return_delivery:
                      url: "{{Spark.mail.aux_data.answer_url}}"
                      method: PUT
                      delivery_mode: required
                      content_var: answer_toml
                      media_type: text/plain
                  - event: write_ready
                    return_delivery:
                      url: "{{Spark.mail.aux_data.firstboot_url}}"
                      method: PUT
                      delivery_mode: required
                      content_var: firstboot_script
                      media_type: text/plain
                """.trimIndent(),
            )

            val deliveryPlan = requireNotNull(manifest.buildLocalDeliveryPreparation(emptyMap()).returnPlan)
            val intent = writeIntent(deliveryPlan)
            val answerUrl = deliveryPlan.variables.getValue("Spark.mail.aux_data.answer_url")
            val firstbootUrl = deliveryPlan.variables.getValue("Spark.mail.aux_data.firstboot_url")
            publishWriteStartCallbacks(
                writeIntent = intent,
                reportStatus = {},
                onDelivery = { delivery ->
                    receivedPaths += "${delivery.method} ${delivery.url}"
                },
            )

            assertTrue(receivedPaths.contains("PUT $answerUrl"))
            assertTrue(receivedPaths.contains("PUT $firstbootUrl"))
        } finally {
        }
    }

    @Test
    fun liveMailroomPublishesMailroomAnswerAndFirstbootPuts() = runBlocking {
        assumeTrue(System.getenv("SPARKWRITER_LIVE_MAILROOM_TEST") == "1")

        val receivedPaths = Collections.synchronizedList(mutableListOf<String>())
        val deliveryErrors = Collections.synchronizedList(mutableListOf<Throwable>())
        val publishLog = Collections.synchronizedList(mutableListOf<String>())
        val mailroomOrigin = liveMailroomOrigin()

        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: proxmox-mailroom-demo-live
              name: Proxmox Mailroom Demo Live
            source:
              id: proxmox-ve-ais-generic-netboot
              name: Proxmox VE AIS generic netboot ISO
              user_supplied: true
              capabilities:
                - netboot-rendezvous
              adaptation:
                status: patchable_on_import
                satisfies:
                  - auto_install_mode
                  - answer_fetch.http
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - mailroom
              base_image_requires:
                - auto_install_mode
                - answer_fetch.http
            boot_patch:
              filesystem: iso9660
              placeholder_patches:
                - candidate_paths:
                    - /auto-installer-mode.toml
                  placeholder: "${mailroomOrigin}/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
                  replacement: "{{Spark.mail.aux_data.answer_url}}"
                  encoding: ascii
                  padding: space
            templates:
              answer:
                - "[first-boot]"
                - url = "{{Spark.mail.aux_data.firstboot_url}}"
              firstboot:
                - "#!/usr/bin/env bash"
                - "echo ready"
            actions:
              on_iso_ready:
                - id: render-answer
                  type: render_template
                  template: answer
                  output_var: answer_toml
                - id: create-answer
                  type: create_artifact
                  artifact_id: answer
                  media_type: text/plain
                  content_var: answer_toml
                - id: render-firstboot
                  type: render_template
                  template: firstboot
                  output_var: firstboot_script
                - id: create-firstboot
                  type: create_artifact
                  artifact_id: firstboot
                  media_type: text/plain
                  content_var: firstboot_script
            host_events:
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.answer_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: answer_toml
                  media_type: text/plain
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.firstboot_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: firstboot_script
                  media_type: text/plain
            """.trimIndent(),
        )

        val deliveryPlan = requireNotNull(manifest.buildLocalDeliveryPreparation(emptyMap()).returnPlan)
        val intent = writeIntent(deliveryPlan)
        val answerUrl = deliveryPlan.variables.getValue("Spark.mail.aux_data.answer_url")
        val firstbootUrl = deliveryPlan.variables.getValue("Spark.mail.aux_data.firstboot_url")
        val rendezvousId = deliveryPlan.variables.getValue("rendezvous_id")
        publishLog += "manually-generated rendezvous=$rendezvousId"
        publishLog += "answer_url=$answerUrl"
        publishLog += "firstboot_url=$firstbootUrl"

        publishWriteStartCallbacks(
            writeIntent = intent,
            reportStatus = {},
            log = { entry ->
                publishLog += "publish: $entry"
                println("publishWriteStartCallbacks: $entry")
            },
            onDelivery = { delivery ->
                receivedPaths += "${delivery.method} ${delivery.url}"
                runCatching {
                    deliverReturnDelivery(
                        method = delivery.method,
                        url = delivery.url,
                        body = delivery.body,
                        mediaType = delivery.mediaType,
                        headers = delivery.headers,
                    )
                }.onFailure { failure ->
                    deliveryErrors += failure
                }
            },
        )

        assertTrue(receivedPaths.contains("PUT $answerUrl"))
        assertTrue(receivedPaths.contains("PUT $firstbootUrl"))
        if (deliveryErrors.isNotEmpty()) {
            throw deliveryErrors.first()
        }

        val answerBody = fetchLiveMailroomArtifact(answerUrl, "[first-boot]")
        assertTrue(answerBody.contains("url ="))
        fetchLiveMailroomArtifact(firstbootUrl, "echo ready")
        Unit
    }

    @Test
    fun liveMailroomManuallyConfiguredRendezvousArtifactsMustBeReadable() = runBlocking {
        assumeTrue(System.getenv("SPARKWRITER_LIVE_MAILROOM_TEST") == "1")

        val receivedPaths = Collections.synchronizedList(mutableListOf<String>())
        val deliveryErrors = Collections.synchronizedList(mutableListOf<Throwable>())
        val publishLog = Collections.synchronizedList(mutableListOf<String>())
        val mailroomOrigin = liveMailroomOrigin()

        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: proxmox-mailroom-demo-live-generated
              name: Proxmox Mailroom Demo Live
            source:
              id: proxmox-ve-ais-generic-netboot
              name: Proxmox VE AIS generic netboot ISO
              user_supplied: true
              capabilities:
                - netboot-rendezvous
              adaptation:
                status: patchable_on_import
                satisfies:
                  - auto_install_mode
                  - answer_fetch.http
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - mailroom
              base_image_requires:
                - auto_install_mode
                - answer_fetch.http
            boot_patch:
              filesystem: iso9660
              placeholder_patches:
                - candidate_paths:
                    - /auto-installer-mode.toml
                  placeholder: "${mailroomOrigin}/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
                  replacement: "{{Spark.mail.aux_data.answer_url}}"
                  encoding: ascii
                  padding: space
            templates:
              answer:
                - "[first-boot]"
                - "url = \"{{Spark.mail.aux_data.firstboot_url}}\""
              firstboot:
                - "#!/usr/bin/env bash"
                - "echo ready"
            actions:
              on_iso_ready:
                - id: render-answer
                  type: render_template
                  template: answer
                  output_var: answer_toml
                - id: create-answer
                  type: create_artifact
                  artifact_id: answer
                  media_type: text/plain
                  content_var: answer_toml
                - id: render-firstboot
                  type: render_template
                  template: firstboot
                  output_var: firstboot_script
                - id: create-firstboot
                  type: create_artifact
                  artifact_id: firstboot
                  media_type: text/plain
                  content_var: firstboot_script
            host_events:
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.answer_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: answer_toml
                  media_type: text/plain
              - event: write_ready
                return_delivery:
                  url: "{{Spark.mail.aux_data.firstboot_url}}"
                  method: PUT
                  delivery_mode: required
                  content_var: firstboot_script
                  media_type: text/plain
            """.trimIndent(),
        )

        val deliveryPlan = requireNotNull(manifest.buildLocalDeliveryPreparation(emptyMap()).returnPlan)
        val intent = writeIntent(deliveryPlan)
        val answerUrl = deliveryPlan.variables.getValue("Spark.mail.aux_data.answer_url")
        val firstbootUrl = deliveryPlan.variables.getValue("Spark.mail.aux_data.firstboot_url")
        val rendezvousId = deliveryPlan.variables.getValue("rendezvous_id")
        publishLog += "manually-generated rendezvous=$rendezvousId"
        publishLog += "answer_url=$answerUrl"
        publishLog += "firstboot_url=$firstbootUrl"

        publishWriteStartCallbacks(
            writeIntent = intent,
            reportStatus = {},
            log = { entry ->
                publishLog += "publish: $entry"
                println("publishWriteStartCallbacks: $entry")
            },
            onDelivery = { delivery ->
                receivedPaths += "${delivery.method} ${delivery.url}"
                runCatching {
                    deliverReturnDelivery(
                        method = delivery.method,
                        url = delivery.url,
                        body = delivery.body,
                        mediaType = delivery.mediaType,
                        headers = delivery.headers,
                    )
                }.onFailure { failure ->
                    deliveryErrors += failure
                }
            },
        )

        assertTrue(receivedPaths.contains("PUT $answerUrl"))
        assertTrue(receivedPaths.contains("PUT $firstbootUrl"))
        if (deliveryErrors.isNotEmpty()) {
            throw deliveryErrors.first()
        }

        val answerBody = fetchLiveMailroomArtifact(answerUrl, "[first-boot]")
        assertTrue(answerBody.contains("url ="))
        fetchLiveMailroomArtifact(firstbootUrl, "echo ready")
        Unit
    }

    @Test
    fun liveProvidedManifestPublishesWriteReadyMailroomArtifacts() = runBlocking {
        val manifestUrl = System.getenv("SPARKWRITER_REAL_MANIFEST_URL")?.trim().orEmpty()
        assumeTrue(manifestUrl.isNotBlank())

        val manifest = installPackageManifestForTest(manifestUrl)
        val values = manifest.activeConfigValues(
            manifest.configFields.associate { field -> field.id to field.default } + mapOf(
                "tailscale_auth_key" to "tskey-test",
                "proxmox_root_password" to "test-root-password",
            ),
        )
        val returnPlan = requireNotNull(manifest.buildLocalDeliveryPreparation(values).returnPlan)
        val deliveries = prepareWriteReadyReturnDeliveries(writeIntent(returnPlan))

        assertEquals(2, deliveries.size)
        val answerDelivery = deliveries.single { it.url.endsWith("/answer") }
        val firstbootDelivery = deliveries.single { it.url.endsWith("/firstboot") }
        assertEquals("PUT", answerDelivery.method)
        assertEquals("PUT", firstbootDelivery.method)
        assertTrue(answerDelivery.url.startsWith("https://mailroom.metalstrapper.net/netboot/v1/rendezvous/"))
        assertTrue(firstbootDelivery.url.startsWith("https://mailroom.metalstrapper.net/netboot/v1/rendezvous/"))

        deliveries.forEach { delivery ->
            deliverReturnDelivery(
                method = delivery.method,
                url = delivery.url,
                body = delivery.body,
                mediaType = delivery.mediaType,
                headers = delivery.headers,
            )
        }

        val answerBody = postLiveMailroomArtifact(answerDelivery.url, "[first-boot]")
        assertTrue(answerBody.contains("/firstboot"))
        fetchLiveMailroomArtifact(firstbootDelivery.url, "tailscale up")
        Unit
    }

    @Test
    fun preparesRenderedWriteCompleteReturnBody() {
        val intent = writeIntent(
            WriteReturnPlan(
                variables = mapOf("generated_proxmox_root_password" to "secret"),
                templates = mapOf("return_body" to """{"status":"{{status}}","password":"{{generated_proxmox_root_password}}"}"""),
                actions = listOf(
                    WorkflowAction(
                        id = "render-return",
                        type = "render_template",
                        template = "return_body",
                        outputVar = "return_json",
                    ),
                ),
                hostEvents = listOf(
                    HostEventSubscription(
                        event = "write_complete",
                        returnDelivery = ReturnDeliverySpec(
                            url = "https://localhost:3001/api/returns/sparkwriter",
                            deliveryMode = "required",
                            contentVar = "return_json",
                        ),
                    ),
                ),
            ),
        )

        val delivery = prepareWriteCompleteReturnDeliveries(intent, mapOf("id" to "receipt-1")).single()

        assertEquals("POST", delivery.method)
        assertEquals("https://localhost:3001/api/returns/sparkwriter", delivery.url)
        assertEquals("application/json", delivery.mediaType)
        assertTrue(delivery.required)
        assertEquals("""{"status":"COMPLETED","password":"secret"}""" + "\n", delivery.body)
    }

    @Test
    fun preparesRenderedPutReturnUrl() {
        val intent = writeIntent(
            WriteReturnPlan(
                variables = mapOf("netboot_rendezvous_label" to "N".repeat(43)),
                templates = mapOf("ipxe" to "#!ipxe\nchain https://example.test/boot.ipxe\n"),
                actions = emptyList(),
                hostEvents = listOf(
                    HostEventSubscription(
                        event = "write_complete",
                        returnDelivery = ReturnDeliverySpec(
                            url = "https://returns.example/api/{{netboot_rendezvous_label}}",
                            method = "PUT",
                            template = "ipxe",
                            mediaType = "application/x-ipxe",
                        ),
                    ),
                ),
            ),
        )

        val delivery = prepareWriteCompleteReturnDeliveries(intent, emptyMap()).single()

        assertEquals("PUT", delivery.method)
        assertEquals("https://returns.example/api/${"N".repeat(43)}", delivery.url)
        assertEquals("application/x-ipxe", delivery.mediaType)
        assertEquals("#!ipxe\nchain https://example.test/boot.ipxe\n", delivery.body)
    }

    @Test
    fun preparesReturnBodyFromArtifactReference() {
        val intent = writeIntent(
            WriteReturnPlan(
                variables = mapOf("status" to "ready"),
                artifacts = mapOf("answer_toml" to "[answer]\nstatus = done\n"),
                actions = emptyList(),
                hostEvents = listOf(
                    HostEventSubscription(
                        event = "write_complete",
                        returnDelivery = ReturnDeliverySpec(
                            url = "https://returns.example/api/answer",
                            method = "PUT",
                            from = "artifacts.answer_toml",
                            deliveryMode = "required",
                            mediaType = "application/toml",
                        ),
                    ),
                ),
            ),
        )

        val delivery = prepareWriteCompleteReturnDeliveries(intent, emptyMap()).single()

        assertEquals(true, delivery.required)
        assertEquals("[answer]\nstatus = done\n", delivery.body)
        assertEquals("PUT", delivery.method)
        assertEquals("application/toml", delivery.mediaType)
    }

    @Test
    fun preparesDefaultWorkflowStartedBodyWhenNoBodySource() {
        val intent = writeIntent(
            WriteReturnPlan(
                variables = mapOf("status" to "ready"),
                hostEvents = listOf(
                    HostEventSubscription(
                        event = "workflow.started",
                        returnDelivery = ReturnDeliverySpec(
                            url = "https://returns.example/api/abc",
                            deliveryMode = "required",
                        ),
                    ),
                ),
            ),
            tags = mapOf(
                "manifest_id" to "manifest-1",
                "package_id" to "pkg-1",
            ),
        )

        val delivery = prepareWorkflowStartedReturnDeliveries(intent).single()

        assertEquals("POST", delivery.method)
        assertEquals("https://returns.example/api/abc", delivery.url)
        assertEquals("application/json", delivery.mediaType)
        assertEquals(true, delivery.required)
        assertEquals("workflow.started", delivery.event)
        assertTrue(delivery.body.contains("\"event\":\"workflow.started\""))
        assertTrue(delivery.body.contains("\"status\":\"RUNNING\""))
        assertTrue(delivery.body.contains("\"manifest_id\":\"manifest-1\""))
        assertTrue(delivery.body.contains("\"package_id\":\"pkg-1\""))
        assertTrue(delivery.body.contains("\"device_id\":\"usb-1\""))
        assertTrue(delivery.body.contains("\"image_uri\":\"content://image\""))
        assertTrue(delivery.body.contains("\"image_sha256\":\"${"c".repeat(64)}\""))
    }

    @Test
    fun preparesWorkflowStartedReturnBodyFromActionPhaseAlias() {
        val intent = writeIntent(
            WriteReturnPlan(
                variables = mapOf("session_id" to "session-1"),
                templates = mapOf(
                    "return_body" to "{\"session\":\"{{session_id}}\",\"event\":\"{{event}}\"}",
                ),
                actionPhases = mapOf(
                    "on_workflow_started" to listOf(
                        WorkflowAction(
                            id = "render-workflow-started",
                            type = "render_template",
                            template = "return_body",
                            outputVar = "return_json",
                        ),
                    ),
                ),
                hostEvents = listOf(
                    HostEventSubscription(
                        event = "workflow.started",
                        returnDelivery = ReturnDeliverySpec(
                            url = "https://localhost:3001/api/returns/sparkwriter",
                            contentVar = "return_json",
                        ),
                    ),
                ),
            ),
        )

        val delivery = prepareWorkflowStartedReturnDeliveries(intent).single()

        assertEquals("{\"session\":\"session-1\",\"event\":\"workflow.started\"}\n", delivery.body)
    }

    @Test(expected = IllegalStateException::class)
    fun requiredWorkflowStartedReturnFailureFailsExecution() = runBlocking {
        val intent = writeIntent(
            WriteReturnPlan(
                hostEvents = listOf(
                    HostEventSubscription(
                        event = "workflow.started",
                        returnDelivery = ReturnDeliverySpec(
                            url = "https://localhost:3001/api/returns/sparkwriter",
                            deliveryMode = "required",
                        ),
                    ),
                ),
            ),
        )

        val delivery = prepareWorkflowStartedReturnDeliveries(intent).single()

        executePreparedReturnDeliveries(
            deliveries = listOf(delivery),
            post = {
                throw IllegalStateException("boom")
            },
        )
    }

    @Test
    fun bestEffortWorkflowStartedReturnFailureDoesNotFailExecution() = runBlocking {
        var called = false

        val intent = writeIntent(
            WriteReturnPlan(
                hostEvents = listOf(
                    HostEventSubscription(
                        event = "workflow.started",
                        returnDelivery = ReturnDeliverySpec(
                            url = "https://localhost:3001/api/returns/sparkwriter",
                            deliveryMode = "best_effort",
                        ),
                    ),
                ),
            ),
        )

        val delivery = prepareWorkflowStartedReturnDeliveries(intent).single()

        executePreparedReturnDeliveries(
            deliveries = listOf(delivery),
            post = {
                called = true
                throw IllegalStateException("boom")
            },
        )

        assertTrue(called)
    }

    @Test
    fun returnDeliveryRefusesUrlThatIsNotHttps() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                deliverReturnDelivery(
                    method = "POST",
                    url = "http://localhost:3001/api/returns/sparkwriter",
                    body = "{}",
                    mediaType = "application/json",
                )
            }
        }
    }

    @Test
    fun workflowStartedCallbacksArePreparedBeforeWriteReadyCallbacks() = runBlocking {
        val deliveryPlan = WriteReturnPlan(
            hostEvents = listOf(
                HostEventSubscription(
                    event = "workflow.started",
                    returnDelivery = ReturnDeliverySpec(
                        url = "https://localhost:3001/api/returns/sparkwriter/started",
                        deliveryMode = "required",
                    ),
                ),
                HostEventSubscription(
                    event = "write_ready",
                    returnDelivery = ReturnDeliverySpec(
                        url = "https://localhost:3001/api/returns/sparkwriter/ready",
                        contentVar = "write_ready_body",
                        deliveryMode = "required",
                    ),
                ),
            ),
            actionPhases = mapOf(
                "on_workflow_started" to listOf(
                    WorkflowAction(
                        id = "ready-workflow-started",
                        type = "render_template",
                        template = "return_started_body",
                        outputVar = "workflow_started",
                    ),
                ),
                "on_write_ready" to listOf(
                    WorkflowAction(
                        id = "ready-write-ready",
                        type = "render_template",
                        template = "return_ready_body",
                        outputVar = "write_ready_body",
                    ),
                ),
            ),
            templates = mapOf(
                "return_started_body" to "workflow started",
                "return_ready_body" to "write ready {{status}}",
            ),
            variables = mapOf(
                "workflow_started" to "workflow_started_body",
                "write_ready_body" to "write_ready_body",
            ),
        )

        val order = publishWriteStartCallbacks(
            writeIntent = writeIntent(deliveryPlan),
            reportStatus = {},
            onDelivery = {},
        )

        assertEquals(listOf("workflow.started", "write_ready"), order)
    }

    @Test
    fun serviceRenderedSeedWithoutWriteReadyDoesNotPublishMailroomArtifact() = runBlocking {
        val deliveredEvents = publishWriteStartCallbacks(
            writeIntent = writeIntent(WriteReturnPlan()),
            reportStatus = {},
            onDelivery = { error("No callbacks should be delivered") },
        )

        assertTrue(deliveredEvents.isEmpty())
    }

    @Test
    fun mailroomWriteReadyDeliveryIsAnonymous() {
        val intent = writeIntent(
            WriteReturnPlan(
                variables = mapOf("netboot_rendezvous_label" to "N".repeat(43)),
                templates = mapOf("ipxe" to "#!ipxe\nchain https://example.test/boot.ipxe\n"),
                hostEvents = listOf(
                    HostEventSubscription(
                        event = "write_ready",
                        returnDelivery = ReturnDeliverySpec(
                            url = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/{{netboot_rendezvous_label}}/boot",
                            method = "PUT",
                            deliveryMode = "required",
                            template = "ipxe",
                            mediaType = "application/x-ipxe",
                        ),
                    ),
                ),
            ),
        )

        val delivery = prepareWriteReadyReturnDeliveries(intent).single()

        assertEquals("PUT", delivery.method)
        assertEquals(
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"N".repeat(43)}/boot",
            delivery.url,
        )
        assertTrue(delivery.headers.isEmpty())
    }

    @Test
    fun compactMailroomWriteReadyDeliveryIsAnonymous() {
        val origin = "https://mailroom.metalstrapper.net"
        val intent = writeIntent(
            WriteReturnPlan(
                variables = mapOf(
                    "Spark.mail.compact.preseed_write_url" to "$origin/compact/v1/artifacts",
                    "locale.locale" to "en_US.UTF-8",
                    "locale.keyboard" to "us",
                ),
                templates = mapOf("preseed" to "d-i debian-installer/locale string {{locale.locale}}\nd-i keyboard-configuration/xkb-keymap select {{locale.keyboard}}\n"),
                hostEvents = listOf(
                    HostEventSubscription(
                        event = "write_ready",
                        returnDelivery = ReturnDeliverySpec(
                            url = "{{Spark.mail.compact.preseed_write_url}}",
                            method = "POST",
                            deliveryMode = "required",
                            template = "preseed",
                            mediaType = "text/plain",
                        ),
                    ),
                ),
            ),
        )

        val delivery = prepareWriteReadyReturnDeliveries(intent).single()

        assertEquals("POST", delivery.method)
        assertEquals("$origin/compact/v1/artifacts", delivery.url)
        assertTrue(delivery.headers.isEmpty())
    }

    @Test
    fun compactMailroomPublishResultReplacesBootPatchPlaceholderUrl() {
        val writeUrl = "https://mailroom.metalstrapper.net/compact/v1/artifacts"
        val placeholderUrl = "http://wri7e.net/AbCd123"
        val publishedUrl = "http://wri7e.net/Zy9Xw8V"
        val intent = writeIntent(
            WriteReturnPlan(
                variables = mapOf(
                    "Spark.mail.compact.preseed_url" to placeholderUrl,
                    "Spark.mail.compact.preseed_write_url" to writeUrl,
                ),
            ),
        ).copy(
            bootPatch = BootPatchPlan(
                kernelArgPatches = listOf(
                    BootKernelArgPatchSpec(
                        configFamily = "grub",
                        selector = BootKernelArgSelector(menuentry = "Install", kernel = "/vmlinuz"),
                        ensureArgs = listOf("url=$placeholderUrl", "auto=true"),
                    ),
                ),
            ),
        )

        val updated = intent.withPublishedCompactArtifact(writeUrl, publishedUrl)

        assertEquals(
            listOf("url=$publishedUrl", "auto=true"),
            updated.bootPatch?.kernelArgPatches?.single()?.ensureArgs,
        )
        assertEquals(publishedUrl, updated.returnPlan?.variables?.get("Spark.mail.compact.preseed_url"))
    }

    @Test
    fun compactMailroomPublishResultReplacesHttpsBootPatchPlaceholderUrl() {
        val writeUrl = "https://mailroom.metalstrapper.net/compact/v1/artifacts"
        val placeholderUrl = "https://wri7e.net/AbCd123"
        val publishedUrl = "https://wri7e.net/Zy9Xw8V"
        val intent = writeIntent(
            WriteReturnPlan(
                variables = mapOf(
                    "Spark.mail.compact.preseed_url" to placeholderUrl,
                    "Spark.mail.compact.preseed_write_url" to writeUrl,
                ),
            ),
        ).copy(
            bootPatch = BootPatchPlan(
                kernelArgPatches = listOf(
                    BootKernelArgPatchSpec(
                        configFamily = "grub",
                        selector = BootKernelArgSelector(menuentry = "Install", kernel = "/vmlinuz"),
                        ensureArgs = listOf("url=$placeholderUrl", "auto=true"),
                    ),
                ),
            ),
        )

        val updated = intent.withPublishedCompactArtifact(writeUrl, publishedUrl, placeholderUrl)

        assertEquals(
            listOf("url=$publishedUrl", "auto=true"),
            updated.bootPatch?.kernelArgPatches?.single()?.ensureArgs,
        )
        assertEquals(publishedUrl, updated.returnPlan?.variables?.get("Spark.mail.compact.preseed_url"))
    }

    @Test
    fun mailroomTomlReturnDeliveryUsesAcceptedContentType() {
        val mediaType = "application/toml".compatibleReturnDeliveryMediaType(
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"N".repeat(43)}/answer",
        )

        assertEquals("text/plain", mediaType)
    }

    @Test
    fun mailroomAcceptedPayloadContentTypesPassThrough() {
        val url = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"N".repeat(43)}/boot"

        assertEquals("application/x-ipxe", "application/x-ipxe".compatibleReturnDeliveryMediaType(url))
        assertEquals("text/plain", "text/plain".compatibleReturnDeliveryMediaType(url))
        assertEquals("text/plain", "text/x-shellscript".compatibleReturnDeliveryMediaType(url))
        assertEquals("application/octet-stream", "application/octet-stream".compatibleReturnDeliveryMediaType(url))
    }

    @Test
    fun compactMailroomReturnDeliveryUsesAcceptedContentTypes() {
        val url = "https://mailroom.metalstrapper.net/compact/v1/artifacts"

        assertEquals("application/x-ipxe", "application/x-ipxe".compatibleReturnDeliveryMediaType(url))
        assertEquals("text/plain", "text/plain".compatibleReturnDeliveryMediaType(url))
        assertEquals("text/plain", "application/toml".compatibleReturnDeliveryMediaType(url))
        assertEquals("text/plain", "text/x-shellscript".compatibleReturnDeliveryMediaType(url))
        assertEquals("application/octet-stream", "application/octet-stream".compatibleReturnDeliveryMediaType(url))
    }

    @Test
    fun nonMailroomReturnDeliveryKeepsMediaType() {
        val url = "https://example.test/a/${"A".repeat(22)}"

        assertEquals("application/toml", "application/toml".compatibleReturnDeliveryMediaType(url))
    }

    @Test(expected = IllegalStateException::class)
    fun requiredWriteReadyFailureFailsExecution() = runBlocking {
        executePreparedReturnDeliveries(
            deliveries = listOf(
                PreparedReturnDelivery(
                    event = "write_ready",
                    method = "PUT",
                    url = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"N".repeat(43)}/boot",
                    body = "#!ipxe\n",
                    mediaType = "application/x-ipxe",
                    required = true,
                ),
            ),
            post = {
                throw IllegalStateException("boom")
            },
        )
    }

    @Test(expected = IllegalStateException::class)
    fun requiredReturnFailureFailsExecution() = runBlocking {
        executePreparedReturnDeliveries(
            deliveries = listOf(
                PreparedReturnDelivery(
                    event = "write_complete",
                    method = "POST",
                    url = "https://localhost:3001/api/returns/sparkwriter",
                    body = "{}",
                    mediaType = "application/json",
                    required = true,
                ),
            ),
            post = {
                throw IllegalStateException("boom")
            },
        )
    }

    @Test
    fun bestEffortReturnFailureDoesNotFailExecution() = runBlocking {
        var called = false

        executePreparedReturnDeliveries(
            deliveries = listOf(
                PreparedReturnDelivery(
                    event = "write_complete",
                    method = "POST",
                    url = "https://localhost:3001/api/returns/sparkwriter",
                    body = "{}",
                    mediaType = "application/json",
                    required = false,
                ),
            ),
            post = {
                called = true
                throw IllegalStateException("boom")
            },
        )

        assertTrue(called)
    }

    private fun writeIntent(
        returnPlan: WriteReturnPlan,
        tags: Map<String, String> = emptyMap(),
    ): WriteIntent = WriteIntent(
        deviceId = "usb-1",
        confirmedDeviceId = "usb-1",
        image = VerifiedImage(
            uri = "content://image",
            sha256 = "c".repeat(64),
            sizeBytes = 1024,
            mediaType = "application/x-iso9660-image",
            provenance = "test",
            displayName = "test.iso",
        ),
        returnPlan = returnPlan,
        tags = tags,
    )

    private fun fetchLiveMailroomArtifact(
        url: String,
        expectedContains: String,
        attempts: Int = 20,
        delayMs: Long = 500L,
    ): String {
        var lastStatus = -1
        var lastBody: String? = null

        repeat(attempts) { attempt ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_000
                readTimeout = 3_000
            }
            try {
                val status = connection.responseCode
                lastStatus = status
                if (status == 200) {
                    val body = connection.inputStream.bufferedReader().readText()
                    lastBody = body
                    if (body.contains(expectedContains)) {
                        return body
                    }
                } else {
                    val body = connection.errorStream?.bufferedReader()?.readText()
                    lastBody = body
                }
            } finally {
                connection.disconnect()
            }

            if (attempt < attempts - 1) {
                Thread.sleep(delayMs)
            }
        }

        val preview = lastBody
            ?.take(512)
            ?.replace("\n", " ")
            ?: "<no response body>"
        throw AssertionError(
            "Expected GET $url to return 200 with body containing '$expectedContains', " +
                "got status=$lastStatus, body-preview='$preview'",
        )
    }

    private fun postLiveMailroomArtifact(
        url: String,
        expectedContains: String,
        attempts: Int = 20,
        delayMs: Long = 500L,
    ): String {
        var lastStatus = -1
        var lastBody: String? = null
        val requestBody = """{"network_interfaces":[]}""".toByteArray(Charsets.UTF_8)

        repeat(attempts) { attempt ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3_000
                readTimeout = 3_000
                doOutput = true
                setRequestProperty("Accept", "application/json, application/toml, text/plain")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use { output -> output.write(requestBody) }
                val status = connection.responseCode
                lastStatus = status
                if (status == 200) {
                    val body = connection.inputStream.bufferedReader().readText()
                    lastBody = body
                    if (body.contains(expectedContains)) {
                        return body
                    }
                } else {
                    val body = connection.errorStream?.bufferedReader()?.readText()
                    lastBody = body
                }
            } finally {
                connection.disconnect()
            }

            if (attempt < attempts - 1) {
                Thread.sleep(delayMs)
            }
        }

        val preview = lastBody
            ?.take(512)
            ?.replace("\n", " ")
            ?: "<no response body>"
        throw AssertionError(
            "Expected POST $url to return 200 with body containing '$expectedContains', " +
                "got status=$lastStatus, body-preview='$preview'",
        )
    }

    private fun installPackageManifestForTest(manifestUrl: String): SparkManifest {
        runtime.validateInstallUrl(manifestUrl)
        val packageRootText = fetchPackageText(manifestUrl)
        val packageRoot = runtime.parsePackageRoot(packageRootText, installOrigin = manifestUrl)
        val workflow = packageRoot.documents.values.firstOrNull { it.role == "workflow" }
            ?: packageRoot.documents.values.firstOrNull { it.role == "ui" }
            ?: error("manifest.json must contain a workflow or ui document")
        val workflowText = workflow.inline?.utf8TextForTest()
            ?: fetchPackageText(URI(manifestUrl).resolve(requireNotNull(workflow.url)).toString())
        verifyPackageSha256(workflowText, workflow.sha256, workflow.role)
        return runtime.parseCombined(
            workflowText,
            installOrigin = manifestUrl,
            packageRootSha256 = packageRoot.manifestRootSha256 ?: packageRootText.sha256ForTest(),
        ).withPackageTemplateFilesForTest(packageRoot, manifestUrl)
    }

    private fun SparkManifest.withPackageTemplateFilesForTest(
        packageRoot: SparkPackageRoot,
        packageUrl: String,
    ): SparkManifest {
        val templateOverrides = packageRoot.files.mapNotNull { (key, file) ->
            val templateId = file.templateId(key) ?: return@mapNotNull null
            val text = file.inline?.utf8TextForTest()
                ?: fetchPackageText(URI(packageUrl).resolve(requireNotNull(file.url)).toString())
            verifyPackageSha256(text, file.sha256, key)
            templateId to text
        }.toMap()
        return if (templateOverrides.isEmpty()) this else copy(templates = templates + templateOverrides)
    }

    private fun fetchPackageText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText().orEmpty()
            }
            if (status !in 200..299) error("GET $url failed with HTTP $status: $body")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun InlineText.utf8TextForTest(): String {
        check(encoding == "utf-8") { "Unsupported inline encoding: $encoding" }
        return text
    }

    private fun verifyPackageSha256(text: String, expected: String, name: String) {
        val actual = text.sha256ForTest()
        check(actual == expected) { "Package item $name SHA-256 mismatch: expected=$expected actual=$actual" }
    }

    private fun String.sha256ForTest(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
