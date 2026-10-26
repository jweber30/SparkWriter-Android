package net.metalstrapper.sparkwriter.manifest

import net.metalstrapper.sparkwriter.fixtures.parseCombined
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestRuntimeTest {
    // These tests exercise external-service features, which the release build disables.
    private val runtime = ManifestRuntime(externalServicesEnabled = true)

    @Test
    fun resolvesRelativePackageReferenceOnSameOrigin() {
        assertEquals(
            "https://packages.example.test/demo/sparkplug.yaml",
            runtime.resolvePackageReferenceUrl(
                "https://packages.example.test/demo/manifest.json",
                "sparkplug.yaml",
            ),
        )
    }

    @Test
    fun rejectsCrossOriginPackageReference() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.resolvePackageReferenceUrl(
                "https://packages.example.test/demo/manifest.json",
                "https://attacker.example/sidecar.yaml",
            )
        }
    }

    @Test
    fun rejectsPackageReferenceProtocolDowngrade() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.resolvePackageReferenceUrl(
                "https://packages.example.test/demo/manifest.json",
                "http://packages.example.test/demo/sidecar.yaml",
            )
        }
    }

    @Test
    fun parsesAndroidSafeWorkflowSubset() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "source": {"id": "ubuntu", "name": "Ubuntu", "url": "https://example.com/u.iso"},
              "config_fields": [{"id": "hostname", "label": "Hostname", "required": true}],
              "wizard": {"pages": [{"id": "identity", "title": "Identity", "fields": ["hostname"]}]},
              "templates": {"meta_data": "local-hostname: {{hostname}}"}
            }
            """.trimIndent(),
        )

        assertEquals("demo", manifest.metadata.id)
        assertEquals("ubuntu", manifest.source?.id)
        assertTrue(manifest.isAndroidRunnable)
    }

    @Test
    fun parses2_3WorkflowManifests() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "source": {"id": "ubuntu", "name": "Ubuntu", "url": "https://example.com/u.iso"}
            }
            """.trimIndent(),
        )

        assertEquals("3.0", manifest.version)
        assertEquals("demo", manifest.metadata.id)
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsUnsupportedWorkflowVersions() {
        runtime.parseCombined(
            """
            {
              "version": "9.9",
              "metadata": {"id": "demo", "name": "Demo"},
              "source": {"id": "ubuntu", "name": "Ubuntu", "url": "https://example.com/u.iso"}
            }
            """.trimIndent(),
        )
    }

    @Test
    fun allowsWorkflowWithoutMetadataManifestRootHash() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "source": {"id": "ubuntu", "name": "Ubuntu", "url": "https://example.com/u.iso"},
              "config_fields": [{"id": "hostname", "label": "Hostname", "required": true}]
            }
            """.trimIndent(),
        )

        assertEquals("demo", manifest.metadata.id)
    }

    @Test
    fun rejectsUnsupportedActionTypes() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                {
                  "version": "3.0",
                  "metadata": {"id": "demo", "name": "Demo"},
                  "actions": {"on_iso_ready": [{"id": "x", "type": "run_asset"}]}
                }
                """.trimIndent(),
            )
        }

        assertEquals("Unsupported action type: run_asset", error.message)
    }

    @Test
    fun rejectsUnsupportedVersionsAndUnknownKeys() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined("""{"version": "2.2", "metadata": {"id": "demo", "name": "Demo"}}""")
        }
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                {
                  "version": "3.0",
                  "metadata": {"id": "demo", "name": "Demo"},
                  "config_fields": [{"id": "token", "obfuscated": true}]
                }
                """.trimIndent(),
            )
        }

        assertEquals("Unsupported config field keys: obfuscated", error.message)
    }

    @Test
    fun rejectsUnsupportedPaddingStrategyAtLoad() {
        val hash = "c".repeat(64)
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                version: "3.0"
                metadata:
                  id: demo
                  name: Demo
                artifacts:
                  answer:
                    media_type: text/plain
                source:
                  id: proxmox
                  sha256: $hash
                installer:
                  scheme: proxmox-auto-install
                  accepts_delivery:
                    - fixed_byte_ranges
                delivery:
                  mode: fixed_byte_ranges
                  base_image_sha256: $hash
                  patches:
                    - id: answer
                      from: artifacts.answer
                      byte_offset: 1024
                      byte_length: 128
                      padding:
                        strategy: whitespace
                """.trimIndent(),
            )
        }

        assertEquals("Unsupported fixed byte-range padding strategy: whitespace", error.message)
    }

    @Test
    fun parsesYamlThroughSameRuntimePath() {
        val manifest = runtime.parseCombined(
            """
            version: '3.0'
            metadata:
              manifest_root_sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
              id: demo-yaml
              name: Demo YAML
            source:
              id: ubuntu
              name: Ubuntu
              capabilities:
                - cloud-init-nocloud
            config_fields:
              - id: hostname
                label: Hostname
                type: text
                required: true
            """.trimIndent(),
        )

        assertEquals("3.0", manifest.version)
        assertEquals("demo-yaml", manifest.metadata.id)
        assertEquals("cloud-init-nocloud", manifest.source?.capabilities?.single())
    }

    @Test
    fun parsesSourceFingerprintIndexFields() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "source": {
                "id": "ubuntu",
                "name": "Ubuntu",
                "sha256": "${"a".repeat(64)}",
                "size_bytes": 123456,
                "first_64k_sha256": "${"b".repeat(64)}",
                "pvd_sha256": "${"c".repeat(64)}"
              }
            }
            """.trimIndent(),
        )

        assertEquals(123456L, manifest.source?.sizeBytes)
        assertEquals("b".repeat(64), manifest.source?.first64KSha256)
        assertEquals("c".repeat(64), manifest.source?.pvdSha256)
    }

    @Test
    fun acceptsPatchableAppendAdapterWithoutSourceHash() {
        val manifest = runtime.parseCombined(patchableAppendManifest())

        assertEquals("patchable_on_import", manifest.source?.adaptation?.status)
        assertEquals("iso9660", manifest.bootPatch?.filesystem)
        assertTrue(manifest.isAndroidRunnable)
    }

    @Test
    fun rejectsUnpinnedAppendAuxiliaryWithoutBootPatch() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(patchableAppendManifest(includeBootPatch = false))
        }
    }

    @Test
    fun rejectsBootPatchWithoutAppendAfterImage() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(patchableAppendManifest(placement = "preallocated_in_base_image"))
        }
    }

    @Test
    fun rejectsUnsupportedBootPatchFilesystem() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(patchableAppendManifest(bootPatchFilesystem = "udf"))
        }
    }

    @Test
    fun usesPlaceholderSelectOptionAsDefault() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "config_fields": [
                {
                  "id": "storage",
                  "label": "Storage",
                  "type": "select",
                  "options": [
                    {"value": "select-storage", "label": "Select storage", "placeholder": true},
                    {"value": "local-lvm", "label": "local-lvm"}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val field = manifest.configFields.single()
        assertEquals("select-storage", field.default)
        assertTrue(field.options.first().placeholder)
    }

    @Test
    fun usesOptionDefaultWhenFieldDefaultIsMissing() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "config_fields": [
                {
                  "id": "network",
                  "label": "Network",
                  "type": "select",
                  "options": [
                    {"value": "dhcp", "label": "DHCP", "default": true},
                    {"value": "static", "label": "Static"}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val field = manifest.configFields.single()
        assertEquals("dhcp", field.default)
        assertTrue(field.options.first().default)
    }

    @Test
    fun parsesConditionalConfigField() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "config_fields": [
                {
                  "id": "network",
                  "label": "Network",
                  "type": "select",
                  "default": "dhcp",
                  "options": [
                    {"value": "dhcp", "label": "DHCP"},
                    {"value": "static", "label": "Static"}
                  ]
                },
                {
                  "id": "ip_address",
                  "label": "IP Address",
                  "when": {"field": "network", "operator": "equals", "value": "static"}
                }
              ]
            }
            """.trimIndent(),
        )

        val field = manifest.configFields.last()
        assertEquals("network", field.whenCondition?.field)
        assertEquals("equals", field.whenCondition?.operator)
        assertEquals("static", field.whenCondition?.value)
    }

    @Test
    fun parsesConfigFieldBindings() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "config_fields": [
                {
                  "id": "timezone",
                  "label": "Timezone",
                  "bindings": [
                    {
                      "consumer": "template",
                      "target": "proxmox_answer",
                      "destination": "global.timezone",
                      "encoding": "toml-string"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val binding = manifest.configFields.single().bindings.single()
        assertEquals("template", binding.consumer)
        assertEquals("proxmox_answer", binding.target)
        assertEquals("global.timezone", binding.destination)
        assertEquals("toml-string", binding.encoding)
    }

    @Test
    fun parsesHttpsFetchConfigField() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "config_fields": [
                {
                  "id": "ssh_keys",
                  "label": "SSH Keys",
                  "type": "multiline",
                  "standard_field": "user.ssh_public_keys",
                  "fetch": {
                    "url": "https://github.com/octocat.keys",
                    "media_type": "text/plain",
                    "max_bytes": 16384
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val fetch = manifest.configFields.single().fetch
        assertEquals("https://github.com/octocat.keys", fetch?.url)
        assertEquals("GET", fetch?.method)
        assertEquals("text/plain", fetch?.mediaType)
        assertEquals(16384L, fetch?.maxBytes)
    }

    @Test
    fun parsesFormHostProfilesAndPrefillButtons() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"id": "demo", "name": "Demo"},
              "config_fields": [
                {"id": "hostname", "label": "Hostname"},
                {"id": "token", "label": "Token"}
              ],
              "form": {
                "host_profiles": [
                  {
                    "id": "lab1",
                    "label": "Lab 1",
                    "fields": {"hostname": "lab-one", "token": null},
                    "immutable_fields": ["token"]
                  }
                ],
                "buttons": [
                  {
                    "id": "prefill",
                    "label": "Admin Prefill",
                    "fetch": {"url": "https://prefill.example/hosts/lab1"},
                    "field_bindings": {"hostname": "host.name", "token": "host.secret"},
                    "immutable_fields": ["token"]
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val profile = manifest.form.hostProfiles.single()
        assertEquals("Lab 1", profile.label)
        assertEquals("lab-one", profile.fields["hostname"])
        assertEquals("", profile.fields["token"])
        assertEquals(listOf("token"), profile.immutableFields)
        val button = manifest.form.buttons.single()
        assertEquals("Admin Prefill", button.label)
        assertEquals("host.name", button.fieldBindings["hostname"])
        assertEquals(listOf("token"), button.immutableFields)
    }

    @Test
    fun rejectsHostProfileUnknownFields() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                {
                  "version": "3.0",
                  "metadata": {"id": "demo", "name": "Demo"},
                  "config_fields": [{"id": "hostname", "label": "Hostname"}],
                  "form": {
                    "host_profiles": [
                      {"id": "lab1", "label": "Lab 1", "fields": {"missing": "value"}}
                    ]
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsRemotePrefillUnknownFields() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                {
                  "version": "3.0",
                  "metadata": {"id": "demo", "name": "Demo"},
                  "config_fields": [{"id": "hostname", "label": "Hostname"}],
                  "form": {
                    "buttons": [
                      {
                        "id": "prefill",
                        "label": "Prefill",
                        "fetch": {"url": "https://prefill.example/host"},
                        "field_bindings": {"missing": "host.name"}
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsInsecureRemotePrefillUrls() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                {
                  "version": "3.0",
                  "metadata": {"id": "demo", "name": "Demo"},
                  "config_fields": [{"id": "hostname", "label": "Hostname"}],
                  "form": {
                    "buttons": [
                      {
                        "id": "prefill",
                        "label": "Prefill",
                        "fetch": {"url": "http://prefill.example/host"},
                        "field_bindings": {"hostname": "host.name"}
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsPrefillTargetsForHiddenFields() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                {
                  "version": "3.0",
                  "metadata": {"id": "demo", "name": "Demo"},
                  "config_fields": [
                    {"id": "hostname", "label": "Hostname"},
                    {"id": "secret", "label": "Secret", "hidden": true}
                  ],
                  "form": {
                    "host_profiles": [
                      {"id": "lab1", "label": "Lab 1", "fields": {"secret": "value"}}
                    ]
                  }
                }
                """.trimIndent(),
            )
        }
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                {
                  "version": "3.0",
                  "metadata": {"id": "demo", "name": "Demo"},
                  "config_fields": [
                    {"id": "hostname", "label": "Hostname"},
                    {"id": "secret", "label": "Secret", "hidden": true}
                  ],
                  "form": {
                    "buttons": [
                      {
                        "id": "prefill",
                        "label": "Prefill",
                        "fetch": {"url": "https://prefill.example/host"},
                        "field_bindings": {"secret": "host.secret"}
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsInsecureConfigFieldFetchUrls() {
        runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "config_fields": [
                {
                  "id": "ssh_keys",
                  "label": "SSH Keys",
                  "fetch": {"url": "http://github.com/octocat.keys"}
                }
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun parsesCommonFieldPresentationHints() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "config_fields": [
                {
                  "id": "delivery_url",
                  "label": "Delivery URL",
                  "advanced": true,
                  "hidden": true,
                  "disabled": true
                }
              ]
            }
            """.trimIndent(),
        )

        val field = manifest.configFields.single()
        assertTrue(field.advanced)
        assertTrue(field.hidden)
        assertTrue(field.disabled)
    }

    @Test
    fun parsesUiVisibilityConditions() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "ui_visibility": {
                "when": [
                  {"selector": "source_family", "operator": "equals", "value": "proxmox"},
                  {"selector": "source_capabilities", "operator": "contains", "value": "auto_install_mode"}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(2, manifest.uiVisibility.size)
        assertEquals("source_family", manifest.uiVisibility.first().field)
        assertEquals("contains", manifest.uiVisibility.last().operator)
    }

    @Test
    fun rejectsMalformedWizardPages() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                {
                  "version": "3.0",
                  "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
                  "wizard": {"pages": "identity"}
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun sourceAcquireFallsBackToNonBlankSourceUrl() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "source": {
                "id": "ubuntu",
                "name": "Ubuntu",
                "url": "https://example.com/u.iso",
                "acquire": {"kind": "direct"}
              }
            }
            """.trimIndent(),
        )

        assertEquals("https://example.com/u.iso", manifest.source?.acquire?.url)
    }

    @Test
    fun blankSourceUrlDoesNotBecomeAcquireUrl() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "source": {
                "id": "preadapted",
                "name": "User supplied image",
                "url": "",
                "acquire": {"kind": "user_supplied"}
              }
            }
            """.trimIndent(),
        )

        assertEquals(null, manifest.source?.url)
        assertEquals(null, manifest.source?.acquire)
    }

    @Test
    fun parsesTorrentAcquireKindAndArtifact() {
        val manifest = runtime.parseCombined(
            """
            {
              "version": "3.0",
              "metadata": {"manifest_root_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "id": "demo", "name": "Demo"},
              "source": {
                "id": "ubuntu",
                "name": "Ubuntu",
                "acquire": {
                  "kind": "torrent",
                  "url": "https://example.com/ubuntu.torrent",
                  "artifact": "ubuntu.iso"
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("torrent", manifest.source?.acquire?.kind)
        assertEquals("https://example.com/ubuntu.torrent", manifest.source?.acquire?.url)
        assertEquals("ubuntu.iso", manifest.source?.acquire?.artifact)
    }

    @Test
    fun parsesWriteCompleteReturnDelivery() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              manifest_root_sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
              id: demo
              name: Demo
            templates:
              return_body: '{"status":"{{status}}"}'
            actions:
              on_write_complete:
                - id: render-return
                  type: render_template
                  template: return_body
                  output_var: return_json
            host_events:
              - event: write_complete
                return_delivery:
                  url: https://localhost:3001/api/returns/sparkwriter
                  delivery_mode: required
                  content_var: return_json
                  media_type: application/json
            """.trimIndent(),
        )

        val event = manifest.hostEvents.single()
        assertEquals("write_complete", event.event)
        assertEquals("required", event.returnDelivery?.deliveryMode)
        assertEquals("return_json", event.returnDelivery?.contentVar)
    }

    @Test
    fun parsesHostEventsMapSyntax() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
            host_events:
              write.completed:
                - return_delivery:
                    url: https://returns.example/api/returns/sparkwriter
                    method: PUT
                    delivery_mode: required
                    template: return_body
                    media_type: application/json
              write_ready:
                return_delivery:
                  url: https://mailroom.metalstrapper.net/netboot/v1/rendezvous/{{Spark.mail.aux_data.answer_label}}/answer
                  method: PUT
                  from: artifacts.proxmox-answer
            templates:
              return_body: "{}"
            """.trimIndent(),
        )

        val sortedEvents = manifest.hostEvents.sortedBy { it.event }
        assertEquals(listOf("write_complete", "write_ready"), sortedEvents.map { it.event })
        assertEquals("https://returns.example/api/returns/sparkwriter", sortedEvents[0].returnDelivery?.url)
        assertEquals("artifacts.proxmox-answer", sortedEvents[1].returnDelivery?.from)
    }

    @Test
    fun rejectsLegacyReturnDeliveryEndpointSyntax() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                version: "3.0"
                metadata:
                  id: demo
                  name: Demo
                host_events:
                  - event: write_complete
                    endpoint_id: metalstrapper
                """.trimIndent(),
            )
        }

        assertEquals("Unsupported host_events entry keys: endpoint_id", error.message)
    }

    @Test
    fun includesWorkflowStartedHostEventInParsedEvents() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              manifest_root_sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
              id: demo
              name: Demo
            host_events:
              - event: workflow.started
                return_delivery:
                  url: https://localhost:3001/api/returns/unsupported
              - event: write_ready
                return_delivery:
                  url: https://localhost:3001/api/returns/sparkwriter
                  content_var: return_body
            templates:
              return_body: '{}'
            """.trimIndent(),
        )
        assertEquals(2, manifest.hostEvents.size)
        assertEquals("workflow.started", manifest.hostEvents[0].event)
        assertEquals("write_ready", manifest.hostEvents[1].event)
        assertEquals("https://localhost:3001/api/returns/unsupported", manifest.hostEvents[0].returnDelivery?.url)
        assertEquals("https://localhost:3001/api/returns/sparkwriter", manifest.hostEvents[1].returnDelivery?.url)
    }

    @Test
    fun rejectsReturnDeliveryUrlThatIsNotHttps() {
        assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                version: "3.0"
                metadata:
                  manifest_root_sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                  id: demo
                  name: Demo
                host_events:
                  - event: write_ready
                    return_delivery:
                      url: http://localhost:3001/api/returns/sparkwriter
                      content_var: return_body
                templates:
                  return_body: '{}'
                """.trimIndent(),
            )
        }
    }

    @Test
    fun parsesReturnDeliveryFromArtifactReference() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              manifest_root_sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
              id: demo
              name: Demo
            host_events:
              - event: write_ready
                return_delivery:
                  url: https://localhost:3001/api/returns/sparkwriter
                  from: artifacts.answer
                  delivery_mode: required
                  media_type: application/toml
            """.trimIndent(),
        )

        assertEquals("write_ready", manifest.hostEvents.single().event)
        assertEquals("artifacts.answer", manifest.hostEvents.single().returnDelivery?.from)
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsUnsupportedReturnDeliveryMode() {
        runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              manifest_root_sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
              id: demo
              name: Demo
            host_events:
              - event: write_complete
                return_delivery:
                  url: https://localhost:3001/api/returns/sparkwriter
                  delivery_mode: eventually
                  template: return_body
            templates:
              return_body: '{}'
            """.trimIndent(),
        )
    }

    @Test
    fun allowsReturnDeliveryWithoutBodySource() {
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              manifest_root_sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
              id: demo
              name: Demo
            host_events:
              - event: write_complete
                return_delivery:
                  url: https://localhost:3001/api/returns/sparkwriter
                  delivery_mode: best_effort
            """.trimIndent(),
        )
        assertEquals("https://localhost:3001/api/returns/sparkwriter", manifest.hostEvents.single().returnDelivery?.url)
        assertEquals("best_effort", manifest.hostEvents.single().returnDelivery?.deliveryMode)
    }

    @Test
    fun parsesSourceHashAndAuxiliaryGeometry() {
        val hash = "c".repeat(64)
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              manifest_root_sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
              id: demo
              name: Demo
            source:
              id: proxmox
              name: Proxmox
              sha256: $hash
              media_type: application/x-iso9660-image
              user_supplied: true
              adaptation:
                status: preadapted
                satisfies:
                  - auto_install_mode
                human_requirements:
                  - Use an adapted image.
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - auxiliary_partition
              base_image_requires:
                - auto_install_mode
              auxiliary_partition:
                label: PROXMOX-AIS
                filesystem: vfat
            delivery:
              mode: auxiliary_partition
              partition:
                label: PROXMOX-AIS
                filesystem: vfat
                size_mb: 64
                placement: preallocated_in_base_image
                geometry:
                  base_image_sha256: $hash
                  partition_table: gpt
                  partition_number: 3
                  start_lba: 1048576
                  sector_size: 512
                  byte_offset: 536870912
                  byte_length: 67108864
            """.trimIndent(),
        )

        assertEquals(hash, manifest.source?.sha256)
        assertEquals("application/x-iso9660-image", manifest.source?.mediaType)
        assertEquals(true, manifest.source?.userSupplied)
        assertEquals("preadapted", manifest.source?.adaptation?.status)
        assertEquals("auto_install_mode", manifest.source?.adaptation?.satisfies?.single())
        assertTrue(manifest.isAndroidRunnable)
        assertEquals("preallocated_in_base_image", manifest.delivery?.partition?.placement)
        assertEquals(3, manifest.delivery?.partition?.geometry?.partitionNumber)
        assertEquals(536870912L, manifest.delivery?.partition?.geometry?.byteOffset)
    }

    @Test
    fun parsesFixedByteRangeDeliveryAndMarksProducedAdaptationUnavailable() {
        val hash = "c".repeat(64)
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              manifest_root_sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
              id: demo
              name: Demo
            artifacts:
              answer:
                media_type: text/plain
            source:
              id: proxmox
              name: Proxmox
              sha256: $hash
              adaptation:
                status: produced
                satisfies:
                  - auto_install_mode
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - fixed_byte_ranges
              base_image_requires:
                - auto_install_mode
            delivery:
              mode: fixed_byte_ranges
              base_image_sha256: $hash
              patches:
                - id: answer
                  from: artifacts.answer
                  media_type: application/toml
                  logical_name: answer.toml
                  byte_offset: 1024
                  byte_length: 128
                  placeholder:
                    encoding: zeroes
                    sha256: ${"0".repeat(64)}
                  padding:
                    strategy: toml-comment
            """.trimIndent(),
        )

        assertEquals("fixed_byte_ranges", manifest.delivery?.mode)
        assertEquals("answer.toml", manifest.delivery?.patches?.single()?.logicalName)
        assertFalse(manifest.isAndroidRunnable)
        assertTrue(manifest.unsupportedReasons.single().contains("adapting service"))
    }

    @Test
    fun acceptsAppendAfterImageAuxiliaryDeliveryWithoutGeometry() {
        val hash = "c".repeat(64)
        val manifest = runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: ubuntu-demo
              name: Ubuntu Demo
            artifacts:
              user-data:
                media_type: text/plain
              meta-data:
                media_type: text/plain
            source:
              id: ubuntu
              name: Ubuntu
              sha256: $hash
            installer:
              scheme: ubuntu-nocloud
              accepts_delivery:
                - auxiliary_partition
              auxiliary_partition:
                label: CIDATA
                filesystem: vfat
                required_files:
                  - /user-data
                  - /meta-data
            delivery:
              mode: auxiliary_partition
              partition:
                label: CIDATA
                filesystem: vfat
                size_mb: 64
                placement: append_after_image
              files:
                /user-data:
                  artifact_id: user-data
                /meta-data:
                  artifact_id: meta-data
            """.trimIndent(),
        )

        assertEquals("append_after_image", manifest.delivery?.partition?.placement)
        assertEquals(null, manifest.delivery?.partition?.geometry)
        assertTrue(manifest.isAndroidRunnable)
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsPreallocatedAuxiliaryDeliveryWithoutGeometry() {
        val hash = "c".repeat(64)
        runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              id: demo
              name: Demo
            source:
              id: proxmox
              name: Proxmox
              sha256: $hash
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - auxiliary_partition
              auxiliary_partition:
                label: PROXMOX-AIS
                filesystem: vfat
            delivery:
              mode: auxiliary_partition
              partition:
                label: PROXMOX-AIS
                filesystem: vfat
                size_mb: 64
                placement: preallocated_in_base_image
            """.trimIndent(),
        )
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsOverlappingFixedByteRangePatches() {
        val hash = "c".repeat(64)
        runtime.parseCombined(
            """
            version: "3.0"
            metadata:
              manifest_root_sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
              id: demo
              name: Demo
            source:
              id: proxmox
              name: Proxmox
              sha256: $hash
            installer:
              scheme: proxmox-auto-install
              accepts_delivery:
                - fixed_byte_ranges
            delivery:
              mode: fixed_byte_ranges
              base_image_sha256: $hash
              patches:
                - id: one
                  artifact_id: one
                  byte_offset: 10
                  byte_length: 10
                - id: two
                  artifact_id: two
                  byte_offset: 19
                  byte_length: 10
            """.trimIndent(),
        )
    }

    @Test
    fun parsesPackageRootWithCrossOriginHashedSidecars() {
        val packageRoot = runtime.parsePackageRoot(
            """
            {
              "version": "3.0",
              "kind": "sparkplug.manifest",
              "metadata": {
                "manifest_root_sha256": "ef02e05112ceb0a8f8af1d08242fa39703e7e839e561e25e8f2a9b624d1af94b",
                "id": "demo",
                "name": "Demo"
              },
              "documents": {
                "main": {
                  "role": "workflow",
                  "url": "https://publisher.example/workflow.yaml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                },
                "image": {
                  "role": "image",
                  "url": "https://publisher.example/image.yaml",
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                }
              },
              "files": {
                "seed": {
                  "url": "https://cdn.example/seed/user-data.yaml",
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "media_type": "application/yaml"
                }
              }
            }
            """.trimIndent(),
            installOrigin = "https://spark.metalstrapper.net/p/demo/manifest.json",
        )

        assertEquals("sparkplug.manifest", packageRoot.kind)
        assertEquals("https://cdn.example/seed/user-data.yaml", packageRoot.files["seed"]?.url)
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsPackageRootWithMetadataSignature() {
        runtime.parsePackageRoot(
            """
            {
              "version": "3.0",
              "kind": "sparkplug.manifest",
              "metadata": {
                "id": "demo",
                "name": "Demo",
                "signature": {
                  "algorithm": "openssh-ssh-ed25519",
                  "signed_payload_version": 1,
                  "openssh": "-----BEGIN SSH SIGNATURE-----..."
                }
              },
              "documents": {
                "workflow": {
                  "role": "workflow",
                  "url": "sparkplug.yaml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                },
                "image": {
                  "role": "image",
                  "url": "image.yaml",
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                }
              }
            }
            """.trimIndent(),
            installOrigin = "https://spark.metalstrapper.net/p/demo/manifest.json",
        )
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsPackageRootWithoutManifestRootHash() {
        runtime.parsePackageRoot(
            """
            {
              "version": "3.0",
              "kind": "sparkplug.manifest",
              "metadata": {
                "id": "demo",
                "name": "Demo"
              },
              "documents": {
                "workflow": {
                  "role": "workflow",
                  "url": "sparkplug.yaml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                },
                "image": {
                  "role": "image",
                  "url": "image.yaml",
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                }
              }
            }
            """.trimIndent(),
            installOrigin = "https://spark.metalstrapper.net/p/demo/manifest.json",
        )
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsPackageRootWithWrongManifestRootHash() {
        runtime.parsePackageRoot(
            """
            {
              "version": "3.0",
              "kind": "sparkplug.manifest",
              "metadata": {
                "manifest_root_sha256": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                "id": "demo",
                "name": "Demo"
              },
              "documents": {
                "workflow": {
                  "role": "workflow",
                  "url": "sparkplug.yaml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                },
                "image": {
                  "role": "image",
                  "url": "image.yaml",
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                }
              }
            }
            """.trimIndent(),
            installOrigin = "https://spark.metalstrapper.net/p/demo/manifest.json",
        )
    }

    @Test
    fun parsesUnsignedPackageRoot() {
        val packageRoot = runtime.parsePackageRoot(
            """
            {
              "version": "3.0",
              "kind": "sparkplug.manifest",
              "metadata": {
                "manifest_root_sha256": "cb60623de812e75c2e3c520a7553d2aab4e7ba336dd67cd1b5971271e62f03ee",
                "id": "demo",
                "name": "Demo"
              },
              "documents": {
                "workflow": {
                  "role": "workflow",
                  "url": "sparkplug.yaml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                },
                "image": {
                  "role": "image",
                  "url": "image.yaml",
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                }
              }
            }
            """.trimIndent(),
            installOrigin = "https://spark.metalstrapper.net/p/demo/manifest.json",
        )

        assertEquals("3.0", packageRoot.version)
        assertEquals("Demo", packageRoot.metadata.name)
    }

    @Test
    fun parsesUnsignedPackageRootWithOtherId() {
        val packageRoot = runtime.parsePackageRoot(
            """
            {
              "version": "3.0",
              "kind": "sparkplug.manifest",
              "metadata": {
                "manifest_root_sha256": "4ab8ba14c7cfd5101635f884f78cc41851d9f2008dcd621a7cbfe312f4ac6cd2",
                "id": "demo-v23",
                "name": "Demo v3.0"
              },
              "documents": {
                "workflow": {
                  "role": "workflow",
                  "url": "sparkplug.yaml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                },
                "image": {
                  "role": "image",
                  "url": "image.yaml",
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                }
              }
            }
            """.trimIndent(),
            installOrigin = "https://spark.metalstrapper.net/p/demo-v23/manifest.json",
        )

        assertEquals("3.0", packageRoot.version)
        assertEquals("Demo v3.0", packageRoot.metadata.name)
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsUnsupportedPackageRootVersions() {
        runtime.parsePackageRoot(
            """
            {
              "version": "9.9",
              "kind": "sparkplug.manifest",
              "metadata": {
                "manifest_root_sha256": "c18541a5d07a488566dd0e50360a621cb320136cbc3c6937d2341ba7c1168cf7",
                "id": "demo",
                "name": "Demo"
              },
              "documents": {
                "workflow": {
                  "role": "workflow",
                  "url": "sparkplug.yaml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                },
                "image": {
                  "role": "image",
                  "url": "image.yaml",
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                }
              }
            }
            """.trimIndent(),
            installOrigin = "https://spark.metalstrapper.net/p/demo/manifest.json",
        )
    }

    @Test
    fun rejectsPackageRootWithUiDocument() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parsePackageRoot(
            """
            {
              "version": "3.0",
              "kind": "sparkplug.manifest",
              "metadata": {
                "manifest_root_sha256": "523399fdc3d4f7966ab3063dfac075183440010f6d99f0dd8079cab0ed07eb57",
                "id": "demo",
                "name": "Demo"
              },
              "documents": {
                "ui": {
                  "role": "ui",
                  "url": "ui.yaml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }
              }
            }
            """.trimIndent(),
            installOrigin = "https://spark.metalstrapper.net/p/demo/manifest.json",
            )
        }

        assertEquals("Unsupported manifest.json document roles: ui", error.message)
    }

    @Test
    fun allowsLocalhostInstallUrls() {
        assertEquals("localhost", runtime.validateInstallUrl("http://localhost:8080/manifest.json").host)
        assertEquals("127.0.0.1", runtime.validateInstallUrl("http://127.0.0.1/manifest.json").host)
        assertEquals("[::1]", runtime.validateInstallUrl("http://[::1]/manifest.json").host)
        assertEquals("10.0.2.2", runtime.validateInstallUrl("http://10.0.2.2:8080/manifest.json").host)
        assertEquals("10.0.3.2", runtime.validateInstallUrl("http://10.0.3.2:8080/manifest.json").host)
        assertEquals("penguin.linux.test", runtime.validateInstallUrl("http://penguin.linux.test:3001/manifest.json").host)
    }

    @Test
    fun allowsAndroidDeviceSubnetInstallUrls() {
        assertEquals("100.115.92.0", runtime.validateInstallUrl("http://100.115.92.0:3001/manifest.json").host)
        assertEquals("100.115.92.26", runtime.validateInstallUrl("http://100.115.92.26:3001/api/manifests/persistent/manifest.json").host)
        assertEquals("100.115.92.255", runtime.validateInstallUrl("http://100.115.92.255:3001/manifest.json").host)
    }

    @Test
    fun allowsLocalhostPackageSidecars() {
        val packageRoot = runtime.parsePackageRoot(
            """
            {
              "version": "3.0",
              "kind": "sparkplug.manifest",
              "metadata": {
                "manifest_root_sha256": "c806015ab343362af40d9ee63ab5f828dfe72ae06bcd0ae81f2d6433243c238c",
                "id": "demo",
                "name": "Demo"
              },
              "documents": {
                "workflow": {
                  "role": "workflow",
                  "url": "http://localhost:8080/workflow.yaml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                },
                "image": {
                  "role": "image",
                  "url": "http://localhost:8080/image.yaml",
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                }
              }
            }
            """.trimIndent(),
            installOrigin = "http://localhost:8080/manifest.json",
        )

        assertEquals("http://localhost:8080/workflow.yaml", packageRoot.documents["workflow"]?.url)
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsInsecureInstallUrls() {
        runtime.validateInstallUrl("http://example.com/manifest.json")
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsNearbyInsecureAndroidDeviceSubnetInstallUrls() {
        runtime.validateInstallUrl("http://100.115.93.26:3001/manifest.json")
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsInstallUrlsThatDoNotTargetManifestJson() {
        runtime.validateInstallUrl("https://example.com/sparkplug.yaml")
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsMixedWritingSystemsWithinOneHostnameLabel() {
        runtime.validateInstallUrl("https://pаypal.example/manifest.json")
    }

    @Test
    fun allowsDifferentWritingSystemsInDifferentHostnameLabels() {
        runtime.validateInstallUrl("https://日本.example/manifest.json")
    }

    @Test
    fun allowsJapaneseWritingSystemsWithinOneHostnameLabel() {
        runtime.validateInstallUrl("https://日本ご.example/manifest.json")
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsInvisibleCharactersInHostnameLabels() {
        runtime.validateInstallUrl("https://exam\u200bple.com/manifest.json")
    }

    @Test
    fun extractsManifestUrlFromSparkPluginAddUri() {
        val manifestUrl = runtime.parseSparkUri(
            "spark://plugin/add?manifest=https%3A%2F%2Fexample.com%2Fspark.yaml",
        )

        assertEquals("https://example.com/spark.yaml", manifestUrl)
    }

    @Test
    fun extractsManifestUrlWithNestedQueryFromSparkPluginAddUri() {
        val manifestUrl = runtime.parseSparkUri(
            "spark://plugin/add?manifest=http%3A%2F%2Flocalhost%3A3001%2Fapi%2Fmanifests%2Fcbb4cd9b-ac44-4c64-b885-a9a47d81b41c%2Fmanifest.json%3Fhostname%3Dpve1-test.srv.home.arpa%26baseUrl%3Dhttp%253A%252F%252Flocalhost%253A3001",
        )

        assertEquals(
            "http://localhost:3001/api/manifests/cbb4cd9b-ac44-4c64-b885-a9a47d81b41c/manifest.json?hostname=pve1-test.srv.home.arpa&baseUrl=http%3A%2F%2Flocalhost%3A3001",
            manifestUrl,
        )
    }

    @Test
    fun parsesStructuredKernelArgBootPatch() {
        val manifest = runtime.parseCombined(structuredKernelArgPatchManifest())

        val patches = requireNotNull(manifest.bootPatch).kernelArgPatches
        assertEquals(2, patches.size)
        assertEquals("Graphical install", patches.first().selector.menuentry)
        assertEquals("/install.amd/vmlinuz", patches.first().selector.kernel)
        assertEquals(listOf("auto", "file=/preseed.cfg"), patches.first().ensureArgs)
    }

    @Test
    fun parsesTextBootPatches() {
        val manifest = runtime.parseCombined(structuredKernelArgPatchManifest(includeTextPatch = true))

        val patches = requireNotNull(manifest.bootPatch).textPatches
        assertEquals(1, patches.size)
        assertEquals("replace_once", patches.single().kind)
        assertEquals(listOf("/boot/grub/grub.cfg"), patches.single().candidatePaths)
        assertEquals("Graphical install", patches.single().find)
        assertEquals("Automated install", patches.single().replace)
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsGrubKernelArgPatchWithoutKernelSelector() {
        runtime.parseCombined(structuredKernelArgPatchManifest(grubSelector = "\"menuentry\": \"Graphical install\""))
    }

    @Test
    fun parsesScriptOnlyNetbootBootPatchWithoutDelivery() {
        val manifest = runtime.parseCombined(netbootScriptPatchManifest())

        val patch = requireNotNull(manifest.bootPatch).scriptPatches.single()
        assertEquals("netboot_xyz_autoexec", patch.kind)
        assertEquals(listOf("/autoexec.ipxe"), patch.candidatePaths)
        assertEquals("/esp.img", patch.espImagePath)
        assertEquals("/autoexec.ipxe", patch.espScriptPath)
        assertTrue(patch.script.contains("mailroom.metalstrapper.net/netboot/v1/rendezvous"))
        assertTrue(patch.script.contains("/boot"))
        assertEquals(null, manifest.delivery)
    }

    @Test
    fun parsesMailroomBootPlaceholderPatchWithBootArtifactLabel() {
        val manifest = runtime.parseCombined(
            embeddedIpxePlaceholderPatchManifest(
                capabilities = listOf("netboot-rendezvous"),
                family = "netboot",
                placeholder = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(96)}",
                replacement = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot",
            ),
        )

        val patch = requireNotNull(manifest.bootPatch).placeholderPatches.single()

        assertTrue(manifest.isAndroidRunnable)
        assertEquals("https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<rendezvous-id>/boot", patch.replacement)
    }

    @Test
    fun rejectsMailroomNetbootPatchWithoutNetbootRendezvousCapability() {
        val manifest = runtime.parseCombined(netbootScriptPatchManifest(capabilities = emptyList()))

        assertFalse(manifest.isAndroidRunnable)
        assertTrue(manifest.unsupportedReasons.any { it.contains("netboot-rendezvous") })
    }

    @Test
    fun rejectsUnsupportedHostEvent() {
        val error = assertThrows(ManifestValidationException::class.java) {
            runtime.parseCombined(
                """
                version: "3.0"
                metadata:
                  id: host-events
                  name: Host Events
                host_events:
                  - event: unsupported.event
                    return_delivery:
                      url: https://example.com/return
                """.trimIndent(),
            )
        }

        assertEquals("Unsupported host event: unsupported.event", error.message)
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsNetbootScriptPatchWithoutIpxeShebang() {
        runtime.parseCombined(netbootScriptPatchManifest(script = "dhcp\nchain https://example.com/script.ipxe\n"))
    }

    @Test
    fun parsesEmbeddedIpxePlaceholderBootPatchWithoutDelivery() {
        val manifest = runtime.parseCombined(embeddedIpxePlaceholderPatchManifest())

        val patch = requireNotNull(manifest.bootPatch).placeholderPatches.single()
        assertEquals(listOf("/ipxe.lkrn"), patch.candidatePaths)
        assertTrue(patch.placeholder.startsWith("http://mailroom.invalid/placeholder/"))
        assertEquals("http://10.0.0.5/jobs/abc123/boot.ipxe", patch.replacement)
        assertEquals("ascii", patch.encoding)
        assertEquals("space", patch.padding)
        assertEquals(null, manifest.delivery)
    }

    @Test
    fun parsesUtf8PlaceholderPatchUsingByteLengthCapacity() {
        val manifest = runtime.parseCombined(
            embeddedIpxePlaceholderPatchManifest(
                placeholder = "東京東京",
                replacement = "München",
                encoding = "utf-8",
            ),
        )

        val patch = requireNotNull(manifest.bootPatch).placeholderPatches.single()
        assertEquals("東京東京", patch.placeholder)
        assertEquals("München", patch.replacement)
        assertEquals("utf-8", patch.encoding)
    }

    @Test
    fun normalizesRelativeBootPatchCandidatePaths() {
        val manifest = runtime.parseCombined(
            embeddedIpxePlaceholderPatchManifest(candidatePath = "auto-installer-mode.toml"),
        )

        val patch = requireNotNull(manifest.bootPatch).placeholderPatches.single()
        assertEquals(listOf("/auto-installer-mode.toml"), patch.candidatePaths)
    }

    @Test(expected = ManifestValidationException::class)
    fun rejectsEmbeddedIpxeReplacementLongerThanPlaceholder() {
        runtime.parseCombined(
            embeddedIpxePlaceholderPatchManifest(
                placeholder = "http://mailroom.invalid/p/0000",
                replacement = "http://10.0.0.5/jobs/abc123/boot.ipxe",
            ),
        )
    }
}

private fun patchableAppendManifest(
    includeBootPatch: Boolean = true,
    placement: String = "append_after_image",
    bootPatchFilesystem: String = "iso9660",
): String = """
{
  "version": "3.0",
  "metadata": {"id": "append-demo", "name": "Append Demo"},
  "source": {
    "id": "ubuntu-user-iso",
    "name": "Ubuntu user ISO",
    "media_type": "application/x-iso9660-image",
    "user_supplied": true,
    "adaptation": {
      "status": "patchable_on_import",
      "satisfies": ["answer_fetch.partition"]
    }
  },
  "installer": {
    "scheme": "ubuntu-autoinstall",
    "accepts_delivery": ["auxiliary_partition"],
    "auxiliary_partition": {
      "label": "CIDATA",
      "filesystem": "vfat",
      "required_files": ["/user-data"]
    }
  },
  "artifacts": {
    "user_data": {"media_type": "text/cloud-config", "template": "user_data"}
  },
  "delivery": {
    "mode": "auxiliary_partition",
    "partition": {
      "label": "CIDATA",
      "filesystem": "vfat",
      "size_mb": 64,
      "placement": "$placement"
    },
    "files": {
      "/user-data": {"artifact_id": "user_data", "media_type": "text/cloud-config"}
    }
  },
  "templates": {"user_data": "#cloud-config"}
  ${if (includeBootPatch) """,
  "boot_patch": {
    "filesystem": "$bootPatchFilesystem",
    "kernel_arg_patches": [
      {
        "candidate_paths": ["/boot/grub/grub.cfg"],
        "config_family": "grub",
        "selector": {"menuentry": "Try or Install Ubuntu", "kernel": "/casper/vmlinuz"},
        "ensure_args": ["autoinstall", "ds=nocloud"]
      }
    ]
  }
  """ else ""}
}
""".trimIndent()

private fun structuredKernelArgPatchManifest(
    grubSelector: String = "\"menuentry\": \"Graphical install\", \"kernel\": \"/install.amd/vmlinuz\"",
    includeTextPatch: Boolean = false,
): String = """
{
  "version": "3.0",
  "metadata": {"id": "debian-autoinstall", "name": "Debian Autoinstall"},
  "source": {
    "id": "debian-user-iso",
    "name": "Debian user ISO",
    "user_supplied": true,
    "adaptation": {
      "status": "patchable_on_import"
    }
  },
  "installer": {
    "scheme": "debian-preseed",
    "accepts_delivery": ["auxiliary_partition"],
    "auxiliary_partition": {
      "label": "SPARKDATA",
      "filesystem": "vfat",
      "required_files": ["/preseed.cfg"]
    }
  },
  "artifacts": {
    "preseed": {"media_type": "text/plain", "template": "preseed"}
  },
  "delivery": {
    "mode": "auxiliary_partition",
    "partition": {
      "label": "SPARKDATA",
      "filesystem": "vfat",
      "size_mb": 64,
      "placement": "append_after_image"
    },
    "files": {
      "/preseed.cfg": {"artifact_id": "preseed", "media_type": "text/plain"}
    }
  },
  "boot_patch": {
    "filesystem": "iso9660",
    ${if (includeTextPatch) """"text_patches": [
      {
        "kind": "replace_once",
        "candidate_paths": ["/boot/grub/grub.cfg"],
        "find": "Graphical install",
        "replace": "Automated install"
      }
    ],""" else ""}
    "kernel_arg_patches": [
      {
        "candidate_paths": ["/boot/grub/grub.cfg"],
        "config_family": "grub",
        "selector": {$grubSelector},
        "ensure_args": ["auto", "file=/preseed.cfg"],
        "insert_before": "---"
      },
      {
        "candidate_paths": ["/isolinux/gtk.cfg"],
        "config_family": "syslinux",
        "selector": {"label": "installgui"},
        "ensure_args": ["auto", "file=/preseed.cfg"],
        "insert_before": "---"
      }
    ]
  },
  "templates": {"preseed": "d-i debian-installer/locale string en_US"}
}
""".trimIndent()

private fun netbootScriptPatchManifest(
    capabilities: List<String> = listOf("netboot-rendezvous"),
    script: String = """
        #!ipxe
        dhcp
        chain --autofree https://mailroom.metalstrapper.net/netboot/v1/rendezvous/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/boot
    """.trimIndent() + "\n",
): String = """
version: "3.0"
metadata:
  id: netboot-rendezvous
  name: Netboot Rendezvous
source:
  id: netboot-xyz-user-iso
  name: netboot.xyz ISO
  media_type: application/x-iso9660-image
  user_supplied: true
  family: netboot
${if (capabilities.isNotEmpty()) capabilities.joinToString(prefix = "  capabilities:\n", separator = "\n", postfix = "\n") { "    - $it" } else ""}
  adaptation:
    status: patchable_on_import
    satisfies:
      - rendezvous_script
boot_patch:
  filesystem: iso9660
  script_patches:
    - kind: netboot_xyz_autoexec
      candidate_paths:
        - /autoexec.ipxe
      esp_image_path: /esp.img
      esp_script_path: /autoexec.ipxe
      script: |
${script.prependIndent("        ")}
""".trimIndent()

private fun embeddedIpxePlaceholderPatchManifest(
    placeholder: String = "http://mailroom.invalid/placeholder/${"0".repeat(64)}",
    replacement: String = "http://10.0.0.5/jobs/abc123/boot.ipxe",
    candidatePath: String = "/ipxe.lkrn",
    family: String? = null,
    capabilities: List<String> = emptyList(),
    encoding: String = "ascii",
): String = """
version: "3.0"
metadata:
  id: ipxe-placeholder-demo
  name: iPXE Placeholder Demo
source:
  id: custom-ipxe-iso
  name: Custom iPXE ISO
  media_type: application/x-iso9660-image
  user_supplied: true
${family?.let { "  family: $it\n" }.orEmpty()}${if (capabilities.isNotEmpty()) capabilities.joinToString(prefix = "  capabilities:\n", separator = "\n", postfix = "\n") { "    - $it" } else ""}
  adaptation:
    status: patchable_on_import
boot_patch:
  filesystem: iso9660
  placeholder_patches:
    - candidate_paths:
        - $candidatePath
      placeholder: "$placeholder"
      replacement: "$replacement"
      encoding: $encoding
      padding: space
""".trimIndent()
