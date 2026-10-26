package net.metalstrapper.sparkwriter.fixtures

object Fixtures {
    val netbootDualProtocolManifestJson: String = """
        {
          "version": "3.0",
          "metadata": {
            "id": "ipxe-rendezvous-dual-protocol",
            "name": "iPXE Netboot Rendezvous (Dual Protocol)",
            "version": "1.0.0",
            "author": "MetalStrapper"
          },
          "source": {
            "id": "sparkwriter-ipxe-dual-protocol",
            "name": "MetalStrapper Dual-Protocol Netboot ISO",
            "media_type": "application/x-iso9660-image",
            "sha256": "e0c61c89800017a098a5c13a9fdeff98670636bd8826b0b0b6dfba147adfe5bc",
            "user_supplied": true,
            "capabilities": [
              "ipxe",
              "patchable-boot-script",
              "netboot-rendezvous"
            ],
            "metadata": {
              "bundled_source_asset": "sparkwriter-ipxe-dual-protocol.iso"
            },
            "adaptation": {
              "status": "patchable_on_import"
            }
          },
          "boot_patch": {
            "filesystem": "iso9660",
            "placeholder_patches": [
              {
                "candidate_paths": ["/autoexec.ipxe", "/boot.ipxe"],
                "placeholder": "http://mailroom.metalstrapper.net/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                "replacement": "http://mailroom.metalstrapper.net/netboot/v1/rendezvous/<label>",
                "encoding": "ascii",
                "padding": "space"
              },
              {
                "candidate_paths": ["/autoexec.ipxe", "/boot.ipxe"],
                "placeholder": "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                "replacement": "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<label>",
                "encoding": "ascii",
                "padding": "space"
              }
            ]
          },
          "templates": {
            "ipxe_payload": "#!ipxe\ndhcp\nchain --autofree https://boot.netboot.xyz\n"
          },
          "host_events": [
            {
              "event": "write_ready",
              "return_delivery": {
                "url": "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/{{netboot_rendezvous_label}}",
                "method": "PUT",
                "delivery_mode": "required",
                "template": "ipxe_payload",
                "media_type": "application/x-ipxe"
              }
            }
          ]
        }
    """.trimIndent()

    val netbootManifestJson: String = """
        {
          "version": "3.0",
          "metadata": {
            "id": "ipxe-rendezvous",
            "name": "iPXE Netboot Rendezvous",
            "version": "1.0.0",
            "author": "MetalStrapper"
          },
          "source": {
            "id": "sparkwriter-ipxe-user-iso",
            "name": "SparkWriter iPXE ISO",
            "family": "netboot",
            "media_type": "application/x-iso9660-image",
            "sha256": "e0c61c89800017a098a5c13a9fdeff98670636bd8826b0b0b6dfba147adfe5bc",
            "user_supplied": true,
            "capabilities": [
              "ipxe",
              "patchable-boot-script",
              "netboot-rendezvous"
            ],
            "adaptation": {
              "status": "patchable_on_import",
              "satisfies": [
                "ipxe_boot"
              ]
            },
            "metadata": {
              "bundled_source_asset": "sparkwriter-ipxe-dual-protocol.iso"
            }
          },
          "boot_patch": {
            "filesystem": "iso9660",
            "placeholder_patches": [
              {
                "candidate_paths": ["/autoexec.ipxe", "/boot.ipxe"],
                "placeholder": "http://mailroom.metalstrapper.net/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                "replacement": "http://mailroom.metalstrapper.net/netboot/v1/rendezvous/<label>",
                "encoding": "ascii",
                "padding": "space"
              },
              {
                "candidate_paths": ["/autoexec.ipxe", "/boot.ipxe"],
                "placeholder": "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                "replacement": "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/<label>",
                "encoding": "ascii",
                "padding": "space"
              }
            ]
          },
          "templates": {
            "ipxe_payload": "#!ipxe\ndhcp\nchain --autofree https://boot.netboot.xyz\n"
          },
          "host_events": [
            {
              "event": "write_ready",
              "return_delivery": {
                "url": "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/{{netboot_rendezvous_label}}",
                "method": "PUT",
                "delivery_mode": "required",
                "template": "ipxe_payload",
                "media_type": "application/x-ipxe"
              }
            }
          ]
        }
    """.trimIndent()

    val minimalManifestJson: String = """
        {
          "version": "3.0",
          "metadata": {
            "id": "ubuntu-autoinstall",
            "name": "Ubuntu Autoinstall",
            "version": "1.0.0",
            "author": "MetalStrapper"
          },
          "source": {
            "id": "ubuntu-26.04-server-autoinstall",
            "name": "Ubuntu 26.04 Server Autoinstall",
            "family": "ubuntu",
            "version": "26.04",
            "url": "https://releases.ubuntu.com/26.04/ubuntu-26.04-live-server-amd64.iso",
            "sha256": "dec49008a71f6098d0bcfc822021f4d042d5f2db279e4d75bdd981304f1ca5d9",
            "capabilities": ["cloud-init-nocloud"]
          },
          "outputs": {"usb": true, "iso": true},
          "installer": {
            "scheme": "ubuntu-nocloud",
            "accepts_delivery": ["auxiliary_partition"],
            "auxiliary_partition": {
              "label": "CIDATA",
              "filesystem": "vfat",
              "required_files": ["/user-data", "/meta-data"]
            }
          },
          "config_fields": [
            {"id": "hostname", "label": "Hostname", "type": "text", "required": true},
            {"id": "username", "label": "Admin Username", "type": "text", "default": "ubuntu", "required": true},
            {
              "id": "apt_proxy",
              "label": "APT Proxy",
              "type": "text",
              "default": "http://apt-proxy.srv.home.arpa:3142",
              "standard_field": "apt.proxy",
              "storage": {"persist": "true"}
            },
            {
              "id": "ssh_public_keys",
              "label": "SSH Public Keys",
              "type": "text",
              "standard_field": "user.ssh_public_keys",
              "storage": {"persist": "true"},
              "description": "Paste one SSH public key (without leading '-')."
            }
          ],
          "wizard": {
            "pages": [
              {"id": "identity", "title": "Identity", "fields": ["hostname", "username", "apt_proxy", "ssh_public_keys"]}
            ]
          },
          "artifacts": {
            "user_data": {
              "artifact_id": "user-data",
              "media_type": "text/cloud-config",
              "template": "user_data"
            },
            "meta_data": {
              "artifact_id": "meta-data",
              "media_type": "text/plain",
              "template": "meta_data"
            }
          },
          "delivery": {
            "mode": "auxiliary_partition",
            "partition": {
              "label": "CIDATA",
              "filesystem": "vfat",
              "size_mb": 64,
              "placement": "append_after_image"
            },
            "files": {
              "/user-data": {
                "artifact_id": "user-data",
                "media_type": "text/cloud-config"
              },
              "/meta-data": {
                "artifact_id": "meta-data",
                "media_type": "text/plain"
              }
            }
          },
          "boot_patch": {
            "filesystem": "iso9660",
            "kernel_arg_patches": [
              {
                "candidate_paths": ["/boot/grub/grub.cfg"],
                "config_family": "grub",
                "selector": {
                  "menuentry": "Try or Install Ubuntu",
                  "kernel": "/casper/vmlinuz"
                },
                "ensure_args": ["autoinstall", "ds=nocloud"],
                "insert_before": "---"
              },
              {
                "candidate_paths": ["/boot/grub/grub.cfg"],
                "config_family": "grub",
                "selector": {
                  "menuentry": "Ubuntu (safe graphics)",
                  "kernel": "/casper/vmlinuz"
                },
                "ensure_args": ["autoinstall", "ds=nocloud"],
                "insert_before": "---"
              }
            ]
          },
          "templates": {
            "user_data": "#cloud-config\nautoinstall:\n  version: 1\n  apt:\n    proxy: \"{{apt_proxy}}\"\n  identity:\n    hostname: \"{{hostname}}\"\n    username: \"{{username}}\"\n    password: \"${'$'}6${'$'}nrlLcG2cx5xOjt1p${'$'}YHedNEzo1adLykz4wbM5QCPhdROHVv.k5XbYOMfXo2Fc5pJ/uwi9sDIq45mvtyrCu9Lx/CO.tIN0ClebuEsSV/\"\n  ssh:\n    install-server: true\n    allow-pw: true\n{% if ssh_public_keys %}\n    authorized-keys:\n      - {{ssh_public_keys}}\n{% endif %}\n  storage:\n    layout:\n      name: direct\n",
            "meta_data": "local-hostname: {{hostname}}"
          }
        }
    """.trimIndent()
}
