package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.BootKernelArgPatchSpec
import net.metalstrapper.sparkwriter.core.BootKernelArgSelector
import net.metalstrapper.sparkwriter.core.BootPatchPlan
import net.metalstrapper.sparkwriter.core.BootPlaceholderPatchSpec
import net.metalstrapper.sparkwriter.core.BootScriptPatchSpec
import net.metalstrapper.sparkwriter.core.BootTextPatchSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class IsoBootPatchWriterTest {
    private val line = "linux /casper/vmlinuz quiet ---"
    private val plan = BootPatchPlan(
        candidatePaths = listOf("/boot/grub/grub.cfg"),
        configFamily = "grub",
        lineMatch = line,
        appendArgs = listOf("autoinstall", "ds=nocloud"),
    )

    @Test
    fun resolvesSyntheticIso9660Path() {
        val iso = syntheticIso("menuentry 'Install'\n$line\n")

        val extent = IsoBootPatchWriter.findCandidateExtent(plan) { offset, length ->
            iso.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

        assertEquals("/boot/grub/grub.cfg", extent.path)
        assertTrue(extent.byteOffset > 0)
        assertTrue(extent.byteLength > line.length)
    }

    @Test
    fun resolvesRockRidgeAlternateNameForTruncatedIso9660Path() {
        val config = """
            mode = "http"
            partition_label = "proxmox-ais"
        """.trimIndent() + "\n"
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            autoInstallerMode = config,
        )

        val extent = IsoBootPatchWriter.findCandidateExtent(
            plan.copy(candidatePaths = listOf("/auto-installer-mode.toml")),
        ) { offset, length -> readIsoRange(iso, offset, length) }

        assertEquals("/auto-installer-mode.toml", extent.path)
        assertEquals(config, readIsoRange(iso, extent.byteOffset, extent.byteLength).toString(Charsets.UTF_8))
    }

    @Test
    fun appendsArgsToSingleExactLine() {
        val original = "menuentry 'Install'\n$line\n".padEnd(96, ' ').toByteArray(Charsets.UTF_8)
        val extent = IsoFileExtent("/boot/grub/grub.cfg", 0, original.size)

        val replacement = IsoBootPatchWriter.replacementBytes(plan, extent, original)
        val text = replacement.toString(Charsets.UTF_8)

        assertTrue(text.contains("$line autoinstall ds=nocloud"))
        assertFalse(text.contains("$line\n"))
        assertEquals(original.size, replacement.size)
    }

    @Test
    fun refusesMissingLine() {
        val original = "linux /other quiet\n".padEnd(96, ' ').toByteArray(Charsets.UTF_8)

        assertThrows(WriteRefusedException::class.java) {
            IsoBootPatchWriter.replacementBytes(plan, IsoFileExtent("/boot/grub/grub.cfg", 0, original.size), original)
        }
    }

    @Test
    fun refusesMultipleMatches() {
        val original = "$line\n$line\n".padEnd(128, ' ').toByteArray(Charsets.UTF_8)

        assertThrows(WriteRefusedException::class.java) {
            IsoBootPatchWriter.replacementBytes(plan, IsoFileExtent("/boot/grub/grub.cfg", 0, original.size), original)
        }
    }

    @Test
    fun refusesReplacementLongerThanFileExtent() {
        val original = "$line\n".toByteArray(Charsets.UTF_8)

        assertThrows(WriteRefusedException::class.java) {
            IsoBootPatchWriter.replacementBytes(plan, IsoFileExtent("/boot/grub/grub.cfg", 0, original.size), original)
        }
    }

    @Test
    fun refusesMissingCandidatePath() {
        val iso = syntheticIso("menuentry 'Install'\n$line\n")
        val missing = plan.copy(candidatePaths = listOf("/isolinux/txt.cfg"))

        assertThrows(WriteRefusedException::class.java) {
            IsoBootPatchWriter.findCandidateExtent(missing) { offset, length ->
                iso.copyOfRange(offset.toInt(), offset.toInt() + length)
            }
        }
    }

    @Test
    fun patchesGrubKernelArgsByMenuentryAndKernel() {
        val iso = syntheticIso(
            grubCfg = """
                menuentry --hotkey=g 'Graphical install' {
                    linux    /install.amd/vmlinuz vga=788 --- quiet 
                    initrd   /install.amd/gtk/initrd.gz
                }
                menuentry --hotkey=i 'Install' {
                    linux    /install.amd/vmlinuz vga=788 --- quiet 
                    initrd   /install.amd/initrd.gz
                }
            """.trimIndent() + "\n",
            padGrub = false,
        )
        val structured = BootPatchPlan(
            kernelArgPatches = listOf(grubPatch(menuentry = "Graphical install")),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val text = readIsoFile(iso, "/boot/grub/grub.cfg")
        assertTrue(text.contains("linux    /install.amd/vmlinuz auto file=/preseed.cfg vga=788 --- quiet "))
        assertTrue(text.contains("menuentry --hotkey=i 'Install' {\n    linux    /install.amd/vmlinuz vga=788 --- quiet "))
    }

    @Test
    fun patchesDebianNetinstAutomatedInstallEntryWithMailroomPreseedUrl() {
        val preseedUrl = "http://mr.metalstrapper.net/a/${"A".repeat(22)}"
        val iso = syntheticIso(
            grubCfg = """
                play 960 440 1 0 4 440 1
                submenu --hotkey=a 'Advanced options ...' {
                    menuentry '... Graphical automated install' {
                        set background_color=black
                        linux    /install.amd/vmlinuz auto=true priority=critical vga=788 --- quiet 
                        initrd   /install.amd/gtk/initrd.gz
                    }
                    menuentry --hotkey=a '... Automated install' {
                        set background_color=black
                        linux    /install.amd/vmlinuz auto=true priority=critical vga=788 --- quiet 
                        initrd   /install.amd/initrd.gz
                    }
                }
                menuentry --hotkey=i 'Install' {
                    set background_color=black
                    linux    /install.amd/vmlinuz vga=788 --- quiet 
                    initrd   /install.amd/initrd.gz
                }
            """.trimIndent() + "\n",
            padGrub = false,
        )
        val debianPatch = BootPatchPlan(
            kernelArgPatches = listOf(
                BootKernelArgPatchSpec(
                    candidatePaths = listOf("/boot/grub/grub.cfg"),
                    configFamily = "grub",
                    selector = BootKernelArgSelector(
                        kernel = "/install.amd/vmlinuz",
                        menuentry = "... Automated install",
                        argsContains = listOf("auto=true", "priority=critical", "vga=788"),
                    ),
                    ensureArgs = listOf("url=$preseedUrl", "auto=true", "priority=critical", "interface=auto"),
                    removeArgs = listOf("priority=critical", "vga=788", "quiet"),
                    removeLinesContaining = listOf("play 960 440 1 0 4 440 1"),
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(debianPatch) { offset, length -> readIsoRange(iso, offset, length) })

        val text = readIsoFile(iso, "/boot/grub/grub.cfg")
        assertEquals(1, Regex("""linux\s+/install\.amd/vmlinuz url=http://mr\.metalstrapper\.net/a/A{22} auto=true priority=critical interface=auto ---""").findAll(text).count())
        assertFalse(text.contains("play 960 440"))
        assertTrue(text.contains("linux    /install.amd/vmlinuz auto=true priority=critical vga=788 --- quiet "))
        assertTrue(text.contains("linux    /install.amd/vmlinuz vga=788 --- quiet "))
        assertEquals(1, Regex("""http://mr\.metalstrapper\.net/a/A{22}""").findAll(text).count())
    }

    @Test
    fun patchesRealDebianNetinstIsoWhenAvailable() {
        val isoFile = File(System.getenv("DEBIAN_NETINST_ISO") ?: "${System.getProperty("user.home")}/debian-13.5.0-amd64-netinst.iso")
        assumeTrue(isoFile.isFile)
        val preseedUrl = "http://mr.metalstrapper.net/a/${"A".repeat(22)}"
        val debianPatch = BootPatchPlan(
            textPatches = listOf(
                BootTextPatchSpec(
                    kind = "replace_once",
                    candidatePaths = listOf("/boot/grub/grub.cfg"),
                    find = """
                        menuentry --hotkey=g 'Graphical install' {
                            set background_color=black
                            linux    /install.amd/vmlinuz vga=788 --- quiet 
                            initrd   /install.amd/gtk/initrd.gz
                        }
                    """.trimIndent() + "\n",
                    replace = """
                        menuentry --hotkey=g 'Automated install' {
                            set background_color=black
                            linux    /install.amd/vmlinuz vga=788 --- quiet 
                            initrd   /install.amd/initrd.gz
                        }
                    """.trimIndent() + "\n",
                ),
            ),
            kernelArgPatches = listOf(
                BootKernelArgPatchSpec(
                    candidatePaths = listOf("/boot/grub/grub.cfg"),
                    configFamily = "grub",
                    selector = BootKernelArgSelector(
                        kernel = "/install.amd/vmlinuz",
                        menuentry = "Automated install",
                        argsContains = listOf("vga=788"),
                    ),
                    ensureArgs = listOf("url=$preseedUrl", "auto=true", "priority=critical", "interface=auto"),
                    removeArgs = listOf("vga=788", "quiet"),
                    removeLinesContaining = listOf(
                        "# Added as a workaround for #1110759",
                        "echo \"Loading bootloader...\"",
                        "play 960 440 1 0 4 440 1",
                    ),
                ),
            ),
        )

        val patches = RandomAccessFile(isoFile, "r").use { iso ->
            IsoBootPatchWriter.buildPatches(debianPatch) { offset, length ->
                iso.readRange(offset, length)
            }
        }
        val grubPatch = patches.single { it.description == "/boot/grub/grub.cfg" }
        val patchedGrub = grubPatch.bytes.toString(Charsets.UTF_8)

        assertTrue(patchedGrub.contains("url=$preseedUrl auto=true priority=critical interface=auto ---"))
        assertTrue(patchedGrub.contains("menuentry --hotkey=g 'Automated install'"))
        assertTrue(patchedGrub.contains("initrd   /install.amd/initrd.gz"))
        assertEquals(1, Regex("""url=http://mr\.metalstrapper\.net/a/A{22} auto=true priority=critical interface=auto""").findAll(patchedGrub).count())
        assertFalse(patchedGrub.contains("play 960 440"))
    }

    @Test
    fun appliesTextPatchesBeforeKernelArgPatchesInSameBootConfig() {
        val iso = syntheticIso(
            grubCfg = """
                menuentry --hotkey=g 'Graphical install' {
                    set background_color=black
                    linux    /install.amd/vmlinuz vga=788 theme=dark --- quiet 
                    initrd   /install.amd/gtk/initrd.gz
                }
                menuentry --hotkey=i 'Install' {
                    set background_color=black
                    linux    /install.amd/vmlinuz vga=788 --- quiet 
                    initrd   /install.amd/initrd.gz
                }
            """.trimIndent() + "\n",
            padGrub = false,
        )
        val patch = BootPatchPlan(
            textPatches = listOf(
                BootTextPatchSpec(
                    kind = "replace_once",
                    candidatePaths = listOf("/boot/grub/grub.cfg"),
                    find = "menuentry --hotkey=g 'Graphical install'",
                    replace = "menuentry --hotkey=g 'Automated install'",
                ),
                BootTextPatchSpec(
                    kind = "replace_once",
                    candidatePaths = listOf("/boot/grub/grub.cfg"),
                    find = "initrd   /install.amd/gtk/initrd.gz",
                    replace = "initrd   /install.amd/initrd.gz",
                ),
            ),
            kernelArgPatches = listOf(
                BootKernelArgPatchSpec(
                    candidatePaths = listOf("/boot/grub/grub.cfg"),
                    configFamily = "grub",
                    selector = BootKernelArgSelector(
                        menuentry = "Automated install",
                        kernel = "/install.amd/vmlinuz",
                        argsContains = listOf("theme=dark"),
                    ),
                    ensureArgs = listOf("url=https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"A".repeat(43)}/preseed", "auto=true"),
                    removeArgs = listOf("vga=788", "quiet"),
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(patch) { offset, length -> readIsoRange(iso, offset, length) })

        val text = readIsoFile(iso, "/boot/grub/grub.cfg")
        assertTrue(text.contains("menuentry --hotkey=g 'Automated install'"))
        assertFalse(text.contains("menuentry --hotkey=g 'Graphical install'"))
        assertTrue(text.contains("initrd   /install.amd/initrd.gz"))
        assertFalse(text.contains("/install.amd/gtk/initrd.gz"))
        assertTrue(text.contains("linux    /install.amd/vmlinuz url=https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"A".repeat(43)}/preseed auto=true theme=dark ---"))
        assertTrue(text.contains("menuentry --hotkey=i 'Install'"))
    }

    @Test
    fun patchesUbuntuDesktopKernelArgsBeforeSeparator() {
        val iso = syntheticIso(
            grubCfg = """
                menuentry "Try or Install Ubuntu" {
                    set gfxpayload=keep
                    linux  /casper/vmlinuz  --- quiet splash
                    initrd /casper/initrd
                }
                menuentry "Ubuntu (safe graphics)" {
                    set gfxpayload=keep
                    linux  /casper/vmlinuz nomodeset  --- quiet splash
                    initrd /casper/initrd
                }
            """.trimIndent() + "\n",
            padGrub = false,
        )
        val ubuntuPatch = BootPatchPlan(
            kernelArgPatches = listOf(
                grubPatch(
                    menuentry = "Try or Install Ubuntu",
                    kernel = "/casper/vmlinuz",
                    ensureArgs = listOf("autoinstall", "ds=nocloud"),
                ),
                grubPatch(
                    menuentry = "Ubuntu (safe graphics)",
                    kernel = "/casper/vmlinuz",
                    ensureArgs = listOf("autoinstall", "ds=nocloud"),
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(ubuntuPatch) { offset, length -> readIsoRange(iso, offset, length) })

        val text = readIsoFile(iso, "/boot/grub/grub.cfg")
        assertTrue(text.contains("linux  /casper/vmlinuz autoinstall ds=nocloud --- quiet splash"))
        assertTrue(text.contains("linux  /casper/vmlinuz autoinstall ds=nocloud nomodeset --- quiet splash"))
    }

    @Test
    fun patchesUbuntuServerKernelArgsWhenMenuentryTitleDiffers() {
        val iso = syntheticIso(
            grubCfg = """
                set timeout=30

                loadfont unicode

                menuentry "Try or Install Ubuntu Server" {
                    set gfxpayload=keep
                    linux  /casper/vmlinuz  --- 
                    initrd /casper/initrd
                }
            """.trimIndent() + "\n",
            padGrub = false,
        )
        val ubuntuPatch = BootPatchPlan(
            kernelArgPatches = listOf(
                grubPatch(
                    menuentry = "Try or Install Ubuntu",
                    kernel = "/casper/vmlinuz",
                    ensureArgs = listOf("autoinstall", "ds=nocloud"),
                ),
                grubPatch(
                    menuentry = "Ubuntu (safe graphics)",
                    kernel = "/casper/vmlinuz",
                    ensureArgs = listOf("autoinstall", "ds=nocloud"),
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(ubuntuPatch) { offset, length -> readIsoRange(iso, offset, length) })

        val text = readIsoFile(iso, "/boot/grub/grub.cfg")
        assertTrue(text.contains("linux  /casper/vmlinuz autoinstall ds=nocloud --- "))
    }

    @Test
    fun refusesGrubFallbackWhenKernelPathIsAmbiguous() {
        val iso = syntheticIso(
            grubCfg = """
                menuentry "First" {
                    linux  /casper/vmlinuz  --- 
                    initrd /casper/initrd
                }
                menuentry "Second" {
                    linux  /casper/vmlinuz nomodeset  --- 
                    initrd /casper/initrd
                }
            """.trimIndent() + "\n",
            padGrub = false,
        )
        val ubuntuPatch = BootPatchPlan(
            kernelArgPatches = listOf(
                grubPatch(
                    menuentry = "Try or Install Ubuntu",
                    kernel = "/casper/vmlinuz",
                    ensureArgs = listOf("autoinstall", "ds=nocloud"),
                ),
            ),
        )

        assertThrows(WriteRefusedException::class.java) {
            IsoBootPatchWriter.buildPatches(ubuntuPatch) { offset, length -> readIsoRange(iso, offset, length) }
        }
    }

    @Test
    fun patchesSyslinuxAppendArgsByLabel() {
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            gtkCfg = """
                default installgui
                label installgui
                	menu label ^Graphical install
                	kernel /install.amd/vmlinuz
                	append vga=788 initrd=/install.amd/gtk/initrd.gz --- quiet 
            """.trimIndent() + "\n",
            padGrub = false,
        )
        val structured = BootPatchPlan(
            kernelArgPatches = listOf(syslinuxPatch(label = "installgui")),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val text = readIsoFile(iso, "/isolinux/gtk.cfg")
        assertTrue(text.contains("append auto file=/preseed.cfg vga=788 initrd=/install.amd/gtk/initrd.gz --- quiet "))
    }

    @Test
    fun replacesDebianGtkSyslinuxAppendLineWithNonGtkInitrdAndUrl() {
        val preseedUrl = "http://mr.metalstrapper.net/a/${"A".repeat(22)}"
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            gtkCfg = """
                default installgui
                label installgui
                 menu label ^Graphical install
                 menu default
                 kernel /install.amd/vmlinuz
                 append vga=788 initrd=/install.amd/gtk/initrd.gz --- quiet 
            """.trimIndent() + "\n",
            padGrub = false,
        )
        val structured = BootPatchPlan(
            kernelArgPatches = listOf(
                BootKernelArgPatchSpec(
                    candidatePaths = listOf("/isolinux/gtk.cfg"),
                    configFamily = "syslinux",
                    selector = BootKernelArgSelector(label = "installgui"),
                    ensureArgs = listOf("initrd=/install.amd/initrd.gz", "auto", "url=$preseedUrl"),
                    removeArgs = listOf("initrd=/install.amd/gtk/initrd.gz", "vga=788", "---", "quiet"),
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val text = readIsoFile(iso, "/isolinux/gtk.cfg")
        assertTrue(text.contains("append initrd=/install.amd/initrd.gz auto url=$preseedUrl "))
        assertFalse(text.contains("initrd=/install.amd/gtk/initrd.gz"))
        assertFalse(text.contains("vga=788"))
        assertFalse(text.contains("---"))
        assertFalse(text.contains("quiet"))
    }

    @Test
    fun replacesDebianSpeechSyslinuxTimeoutWithAutomatedInstall() {
        val preseedUrl = "http://mr.metalstrapper.net/a/${"A".repeat(22)}"
        val original = listOf(
            "label installspk",
            "\tmenu label Install with ^speech synthesis",
            "\tkernel /install.amd/vmlinuz",
            "\tappend vga=788 initrd=/install.amd/gtk/initrd.gz speakup.synth=soft --- quiet ",
            "# timeout to speech-enabled install",
            "timeout 300",
            "ontimeout /install.amd/vmlinuz vga=788 initrd=/install.amd/gtk/initrd.gz speakup.synth=soft --- quiet ",
            "menu autoboot Press a key, otherwise speech synthesis will be started in # second{,s}...",
        ).joinToString(separator = "\n", postfix = "\n")
        val replacement = """
            timeout 30
            ontimeout /install.amd/vmlinuz initrd=/install.amd/initrd.gz auto=true priority=critical interface=auto url=$preseedUrl 
        """.trimIndent() + "\n"
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            spkgtkCfg = original,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            textPatches = listOf(
                BootTextPatchSpec(
                    kind = "replace_once",
                    candidatePaths = listOf("/isolinux/spkgtk.cfg"),
                    find = original,
                    replace = replacement,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val text = readIsoFile(iso, "/isolinux/spkgtk.cfg")
        assertEquals(replacement, text)
    }

    @Test
    fun replacesExistingArgKeysAndAvoidsDuplicates() {
        val iso = syntheticIso(
            grubCfg = """
                menuentry 'Graphical install' {
                    linux    /install.amd/vmlinuz auto=true file=/old.cfg vga=788 --- quiet 
                    initrd   /install.amd/gtk/initrd.gz
                }
            """.trimIndent() + "\n",
            padGrub = false,
        )
        val structured = BootPatchPlan(
            kernelArgPatches = listOf(grubPatch(menuentry = "Graphical install")),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val text = readIsoFile(iso, "/boot/grub/grub.cfg")
        assertTrue(text.contains("linux    /install.amd/vmlinuz auto file=/preseed.cfg vga=788 --- quiet "))
        assertFalse(text.contains("auto=true"))
        assertFalse(text.contains("file=/old.cfg"))
    }

    @Test
    fun appliesMultipleStructuredPatches() {
        val iso = syntheticIso(
            grubCfg = """
                menuentry 'Graphical install' {
                    linux    /install.amd/vmlinuz vga=788 --- quiet 
                    initrd   /install.amd/gtk/initrd.gz
                }
            """.trimIndent() + "\n",
            gtkCfg = """
                label installgui
                	kernel /install.amd/vmlinuz
                	append vga=788 initrd=/install.amd/gtk/initrd.gz --- quiet 
            """.trimIndent() + "\n",
            padGrub = false,
        )
        val structured = BootPatchPlan(
            kernelArgPatches = listOf(
                grubPatch(menuentry = "Graphical install"),
                syslinuxPatch(label = "installgui"),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        assertTrue(readIsoFile(iso, "/boot/grub/grub.cfg").contains("auto file=/preseed.cfg vga=788"))
        assertTrue(readIsoFile(iso, "/isolinux/gtk.cfg").contains("auto file=/preseed.cfg vga=788"))
    }

    @Test
    fun refusesStructuredPatchGrowthBeyondFileAllocation() {
        val oversized = "menuentry 'Graphical install' {\n    linux    /install.amd/vmlinuz " +
            "x".repeat(1946) +
            " --- quiet \n}\n"
        val iso = syntheticIso(grubCfg = oversized, padGrub = false)
        val structured = BootPatchPlan(
            kernelArgPatches = listOf(grubPatch(menuentry = "Graphical install")),
        )

        assertThrows(WriteRefusedException::class.java) {
            IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) }
        }
    }

    @Test
    fun updatesIsoDirectoryLengthWhenStructuredPatchGrowsFile() {
        val original = """
            menuentry 'Graphical install' {
                linux    /install.amd/vmlinuz vga=788 --- quiet 
                initrd   /install.amd/gtk/initrd.gz
            }
        """.trimIndent() + "\n"
        val iso = syntheticIso(grubCfg = original, padGrub = false)
        val structured = BootPatchPlan(
            kernelArgPatches = listOf(grubPatch(menuentry = "Graphical install")),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val extent = IsoBootPatchWriter.findCandidateExtent(
            plan.copy(candidatePaths = listOf("/boot/grub/grub.cfg")),
            { offset, length -> readIsoRange(iso, offset, length) },
        )
        assertEquals(original.length + "auto file=/preseed.cfg ".length, extent.byteLength)
        assertTrue(readIsoFile(iso, "/boot/grub/grub.cfg").contains("auto file=/preseed.cfg"))
    }

    @Test
    fun patchesNetbootXyzAutoexecInIsoRootAndEspImage() {
        val originalScript = "#!ipxe\necho original\n"
        val replacementScript = "#!ipxe\ndhcp\nchain --autofree https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"A".repeat(43)}/boot\n"
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            autoexec = originalScript,
            espImage = syntheticFatImage("autoexec.ipxe", originalScript),
            padGrub = false,
        )
        val structured = BootPatchPlan(
            scriptPatches = listOf(
                BootScriptPatchSpec(
                    kind = "netboot_xyz_autoexec",
                    candidatePaths = listOf("/autoexec.ipxe"),
                    espImagePath = "/esp.img",
                    espScriptPath = "/autoexec.ipxe",
                    script = replacementScript,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        assertEquals(replacementScript, readIsoFile(iso, "/autoexec.ipxe"))
        val patchedEsp = readIsoFileBytes(iso, "/esp.img")
        assertTrue(patchedEsp.toString(Charsets.UTF_8).contains(replacementScript))
        assertEquals(replacementScript.length, patchedEsp.readLe32Int(syntheticFatAutoexecEntryOffset() + 28))
    }

    @Test
    fun patchesEmbeddedIpxePlaceholderWithoutChangingFileLength() {
        val placeholder = "http://mailroom.invalid/placeholder/${"0".repeat(64)}"
        val replacement = "http://10.0.0.5/jobs/abc123/boot.ipxe"
        val binary = "prefix\nthe-url=$placeholder\nsuffix".toByteArray(Charsets.US_ASCII)
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            ipxeBinary = binary,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/ipxe.lkrn"),
                    placeholder = placeholder,
                    replacement = replacement,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val patched = readIsoFileBytes(iso, "/ipxe.lkrn")
        assertEquals(binary.size, patched.size)
        assertTrue(patched.toString(Charsets.US_ASCII).contains(replacement))
        assertTrue(patched.toString(Charsets.US_ASCII).contains(replacement + " ".repeat(placeholder.length - replacement.length)))
        assertFalse(patched.toString(Charsets.US_ASCII).contains(placeholder))
    }

    @Test
    fun patchesUtf8PlaceholderUsingEncodedByteLengths() {
        val placeholder = "東京東京"
        val replacement = "München"
        val placeholderBytes = placeholder.toByteArray(Charsets.UTF_8)
        val binary = "prefix ".toByteArray() + placeholderBytes + " suffix".toByteArray()
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            ipxeBinary = binary,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/ipxe.lkrn"),
                    placeholder = placeholder,
                    replacement = replacement,
                    encoding = "utf-8",
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val patched = readIsoFileBytes(iso, "/ipxe.lkrn")
        assertTrue(patched.toString(Charsets.UTF_8).contains(replacement))
        assertFalse(patched.toString(Charsets.UTF_8).contains(placeholder))
        assertEquals(binary.size, patched.size)
    }

    @Test
    fun refusesEmbeddedIpxeReplacementLongerThanPlaceholder() {
        val placeholder = "http://mailroom.invalid/placeholder/0000"
        val binary = "prefix $placeholder suffix".toByteArray(Charsets.US_ASCII)
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            ipxeBinary = binary,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/ipxe.lkrn"),
                    placeholder = placeholder,
                    replacement = placeholder + "/too-long",
                ),
            ),
        )

        assertThrows(WriteRefusedException::class.java) {
            IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) }
        }
    }

    @Test
    fun compactsMailroomRendezvousUrlIntoLabelPlaceholder() {
        val placeholder = "${"0".repeat(64)}"
        val fullReplacement =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"B".repeat(43)}/answer.toml"
        val compactReplacement = "${"B".repeat(43)}/answer.toml"
        val binary = "prefix $placeholder suffix".toByteArray(Charsets.US_ASCII)
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            ipxeBinary = binary,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/ipxe.lkrn"),
                    placeholder = placeholder,
                    replacement = fullReplacement,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val patched = readIsoFileBytes(iso, "/ipxe.lkrn")
        assertTrue(patched.toString(Charsets.US_ASCII).contains(compactReplacement))
        assertTrue(patched.toString(Charsets.US_ASCII).contains(compactReplacement + " ".repeat(placeholder.length - compactReplacement.length)))
        assertFalse(patched.toString(Charsets.US_ASCII).contains(fullReplacement))
    }

    @Test
    fun padsQuotedTomlPlaceholderOutsideUrlString() {
        val placeholder = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(64)}"
        val replacement =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"B".repeat(43)}/answer.toml"
        val original = """
            mode = "http"
            partition_label = "proxmox-ais"

            [http]
            url = "$placeholder"
        """.trimIndent() + "\n"
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            autoInstallerMode = original,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/auto-installer-mode.toml"),
                    placeholder = placeholder,
                    replacement = replacement,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val patched = readIsoFile(iso, "/auto-installer-mode.toml")
        assertTrue(patched.contains("""url = "$replacement" """))
        assertFalse(patched.contains("""url = "$replacement """))
        assertFalse(patched.contains(placeholder))
    }

    @Test
    fun patchesRendezvousPlaceholderWithSchemeFallback() {
        val plannedPlaceholder =
            "http://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(96)}"
        val actualPlaceholder =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(96)}"
        val replacement = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"B".repeat(43)}/boot"
        val binary = "#!ipxe\nchain --autofree $actualPlaceholder\n".toByteArray(Charsets.US_ASCII)
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            ipxeBinary = binary,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/ipxe.lkrn"),
                    placeholder = plannedPlaceholder,
                    replacement = replacement,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val patched = readIsoFileBytes(iso, "/ipxe.lkrn").toString(Charsets.US_ASCII)
        assertTrue(patched.contains(replacement))
        assertFalse(patched.contains(actualPlaceholder))
    }

    @Test
    fun patchesRendezvousPlaceholderBySlotLengthDifference() {
        val plannedPlaceholder =
            "http://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(96)}"
        val actualPlaceholder =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(64)}"
        val replacement = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"B".repeat(43)}/boot"
        val binary = ("#!ipxe\nchain --autofree $actualPlaceholder\n").toByteArray(Charsets.US_ASCII)
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            ipxeBinary = binary,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/ipxe.lkrn"),
                    placeholder = plannedPlaceholder,
                    replacement = replacement,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val patched = readIsoFileBytes(iso, "/ipxe.lkrn").toString(Charsets.US_ASCII)
        assertTrue(patched.contains(replacement))
        assertTrue(patched.contains(replacement + " ".repeat(actualPlaceholder.length - replacement.length)))
        assertFalse(patched.contains(actualPlaceholder))
    }

    @Test
    fun patchesQuotedTomlRendezvousPlaceholderBySlotLengthDifference() {
        val plannedPlaceholder =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(120)}"
        val actualPlaceholder =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(64)}"
        val replacement =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"B".repeat(43)}/answer"
        val original = """
            mode = "http"
            partition_label = "proxmox-ais"

            [http]
            url = "$actualPlaceholder"
        """.trimIndent() + "\n"
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            autoInstallerMode = original,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/auto-installer-mode.toml"),
                    placeholder = plannedPlaceholder,
                    replacement = replacement,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val patched = readIsoFile(iso, "/auto-installer-mode.toml")
        assertTrue(patched.contains("""url = "$replacement" """))
        assertFalse(patched.contains(actualPlaceholder))
    }

    @Test
    fun patchesBareQuotedTomlRendezvousSlotWhenManifestPlaceholderHasArtifactSuffix() {
        val plannedPlaceholder =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(64)}/answer"
        val actualPlaceholder =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(64)}"
        val replacement =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"B".repeat(43)}/answer"
        val original = """
            mode = "http"
            partition_label = "proxmox-ais"

            [http]
            url = "$actualPlaceholder"
        """.trimIndent() + "\n"
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            autoInstallerMode = original,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/auto-installer-mode.toml"),
                    placeholder = plannedPlaceholder,
                    replacement = replacement,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val patched = readIsoFile(iso, "/auto-installer-mode.toml")
        assertTrue(patched.contains("""url = "$replacement" """))
        assertFalse(patched.contains(actualPlaceholder))
    }

    @Test
    fun replacesGenericProxmoxAutoInstallerModeWithHttpAnswerUrl() {
        val original = """
            mode = "iso"
            partition_label = "proxmox-ais"

            [http]
        """.trimIndent() + "\n"
        val replacement = """
            mode = "http"
            [http]
            url = 'https://wri7e.net/AbCd123'
        """.trimIndent() + "\n"
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            autoInstallerMode = original,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            textPatches = listOf(
                BootTextPatchSpec(
                    kind = "replace_once",
                    candidatePaths = listOf("/auto-installer-mode.toml"),
                    find = original,
                    replace = replacement,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val patched = readIsoFile(iso, "/auto-installer-mode.toml")
        assertEquals(replacement, patched)
        assertFalse(patched.contains("partition_label"))
    }

    @Test
    fun patchesDualProtocolMailroomBootPlaceholders() {
        val rendezvousId = "B".repeat(43)
        val httpPlaceholder =
            "http://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(96)}"
        val httpsPlaceholder =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/${"0".repeat(96)}"
        val httpReplacement =
            "http://mailroom.metalstrapper.net/netboot/v1/rendezvous/$rendezvousId/boot"
        val httpsReplacement =
            "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/$rendezvousId/boot"
        val autoexec = """
            #!ipxe
            dhcp
            chain --autofree $httpPlaceholder
            chain --autofree $httpsPlaceholder
        """.trimIndent() + "\n"
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            autoexec = autoexec,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/autoexec.ipxe", "/boot.ipxe"),
                    placeholder = httpPlaceholder,
                    replacement = httpReplacement,
                ),
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/autoexec.ipxe", "/boot.ipxe"),
                    placeholder = httpsPlaceholder,
                    replacement = httpsReplacement,
                ),
            ),
        )

        applyPatches(iso, IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) })

        val patched = readIsoFile(iso, "/autoexec.ipxe")
        assertTrue(patched.contains("chain --autofree $httpReplacement"))
        assertTrue(patched.contains("chain --autofree $httpsReplacement"))
        assertFalse(patched.contains(httpPlaceholder))
        assertFalse(patched.contains(httpsPlaceholder))
    }

    @Test
    fun reportsLabelInPlaceholderReplacementLengthFailure() {
        val placeholder = "https://mailroom.metalstrapper.net/netboot/v1/rendezvous/0000"
        val binary = "prefix $placeholder suffix".toByteArray(Charsets.US_ASCII)
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            ipxeBinary = binary,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/ipxe.lkrn"),
                    placeholder = placeholder,
                    replacement = "$placeholder/too-long",
                ),
            ),
        )

        val error = assertThrows(WriteRefusedException::class.java) {
            IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) }
        }

        assertTrue(error.message?.contains("label=0000") == true)
    }

    @Test
    fun refusesAmbiguousEmbeddedIpxePlaceholder() {
        val placeholder = "http://mailroom.invalid/placeholder/0000"
        val binary = "$placeholder\n$placeholder\n".toByteArray(Charsets.US_ASCII)
        val iso = syntheticIso(
            grubCfg = "menuentry 'Install'\n$line\n",
            ipxeBinary = binary,
            padGrub = false,
        )
        val structured = BootPatchPlan(
            placeholderPatches = listOf(
                BootPlaceholderPatchSpec(
                    candidatePaths = listOf("/ipxe.lkrn"),
                    placeholder = placeholder,
                    replacement = "http://10.0.0.5/jobs/a/boot.ipxe",
                ),
            ),
        )

        assertThrows(WriteRefusedException::class.java) {
            IsoBootPatchWriter.buildPatches(structured) { offset, length -> readIsoRange(iso, offset, length) }
        }
    }

    private fun grubPatch(
        menuentry: String,
        kernel: String = "/install.amd/vmlinuz",
        ensureArgs: List<String> = listOf("auto", "file=/preseed.cfg"),
    ): BootKernelArgPatchSpec = BootKernelArgPatchSpec(
        candidatePaths = listOf("/boot/grub/grub.cfg"),
        configFamily = "grub",
        selector = BootKernelArgSelector(
            menuentry = menuentry,
            kernel = kernel,
        ),
        ensureArgs = ensureArgs,
    )

    private fun syslinuxPatch(label: String): BootKernelArgPatchSpec = BootKernelArgPatchSpec(
        candidatePaths = listOf("/isolinux/gtk.cfg"),
        configFamily = "syslinux",
        selector = BootKernelArgSelector(label = label),
        ensureArgs = listOf("auto", "file=/preseed.cfg"),
    )

    private fun applyPatches(iso: ByteArray, patches: List<IsoByteRangePatch>) {
        patches.forEach { patch ->
            patch.bytes.copyInto(iso, patch.offset.toInt())
        }
    }

    private fun readIsoFile(iso: ByteArray, path: String): String = readIsoFileBytes(iso, path).toString(Charsets.UTF_8)

    private fun readIsoFileBytes(iso: ByteArray, path: String): ByteArray {
        val extent = IsoBootPatchWriter.findCandidateExtent(
            plan.copy(candidatePaths = listOf(path)),
        ) { offset, length -> readIsoRange(iso, offset, length) }
        return readIsoRange(iso, extent.byteOffset, extent.byteLength)
    }

    private fun readIsoRange(iso: ByteArray, offset: Long, length: Int): ByteArray {
        return iso.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun RandomAccessFile.readRange(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        seek(offset)
        readFully(bytes)
        return bytes
    }

    private fun syntheticIso(
        grubCfg: String,
        gtkCfg: String? = null,
        spkgtkCfg: String? = null,
        autoexec: String? = null,
        espImage: ByteArray? = null,
        ipxeBinary: ByteArray? = null,
        autoInstallerMode: String? = null,
        padGrub: Boolean = true,
    ): ByteArray {
        val image = ByteArray(64 * SECTOR_SIZE)
        val grubBytes = (if (padGrub) grubCfg.padEnd(128, ' ') else grubCfg).toByteArray(Charsets.UTF_8)
        val gtkBytes = gtkCfg?.toByteArray(Charsets.UTF_8)
        val spkgtkBytes = spkgtkCfg?.toByteArray(Charsets.UTF_8)
        val autoexecBytes = autoexec?.toByteArray(Charsets.UTF_8)
        val autoInstallerModeBytes = autoInstallerMode?.toByteArray(Charsets.UTF_8)
        val rootLba = 20
        val bootLba = 21
        val grubDirLba = 22
        val grubCfgLba = 23
        val isolinuxLba = 24
        val gtkCfgLba = 25
        val autoexecLba = 26
        val espLba = 27
        val spkgtkCfgLba = 28
        val ipxeLba = 40
        val autoInstallerModeLba = 41

        primaryVolumeDescriptor(rootLba, SECTOR_SIZE).copyInto(image, 16 * SECTOR_SIZE)
        image[17 * SECTOR_SIZE] = 255.toByte()
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(image, 17 * SECTOR_SIZE + 1)
        image[17 * SECTOR_SIZE + 6] = 1

        directorySector(
            directoryRecord("\u0000", rootLba, SECTOR_SIZE, directory = true),
            directoryRecord("\u0001", rootLba, SECTOR_SIZE, directory = true),
            directoryRecord("BOOT", bootLba, SECTOR_SIZE, directory = true),
            directoryRecord("ISOLINUX", isolinuxLba, SECTOR_SIZE, directory = true),
            *listOfNotNull(
                autoexecBytes?.let { directoryRecord("AUTOEXEC.IPXE;1", autoexecLba, it.size, directory = false) },
                espImage?.let { directoryRecord("ESP.IMG;1", espLba, it.size, directory = false) },
                ipxeBinary?.let { directoryRecord("IPXE.LKRN;1", ipxeLba, it.size, directory = false) },
                autoInstallerModeBytes?.let {
                    directoryRecordWithRockRidgeName(
                        isoName = "AUTO_INS.TOM;1",
                        rockRidgeName = "auto-installer-mode.toml",
                        lba = autoInstallerModeLba,
                        length = it.size,
                        directory = false,
                    )
                },
            ).toTypedArray(),
        ).copyInto(image, rootLba * SECTOR_SIZE)

        directorySector(
            directoryRecord("\u0000", bootLba, SECTOR_SIZE, directory = true),
            directoryRecord("\u0001", rootLba, SECTOR_SIZE, directory = true),
            directoryRecord("GRUB", grubDirLba, SECTOR_SIZE, directory = true),
        ).copyInto(image, bootLba * SECTOR_SIZE)

        directorySector(
            directoryRecord("\u0000", grubDirLba, SECTOR_SIZE, directory = true),
            directoryRecord("\u0001", bootLba, SECTOR_SIZE, directory = true),
            directoryRecord("GRUB.CFG;1", grubCfgLba, grubBytes.size, directory = false),
        ).copyInto(image, grubDirLba * SECTOR_SIZE)

        grubBytes.copyInto(image, grubCfgLba * SECTOR_SIZE)
        if (gtkBytes != null || spkgtkBytes != null) {
            directorySector(
                directoryRecord("\u0000", isolinuxLba, SECTOR_SIZE, directory = true),
                directoryRecord("\u0001", rootLba, SECTOR_SIZE, directory = true),
                *listOfNotNull(
                    gtkBytes?.let { directoryRecord("GTK.CFG;1", gtkCfgLba, it.size, directory = false) },
                    spkgtkBytes?.let { directoryRecord("SPKGTK.CFG;1", spkgtkCfgLba, it.size, directory = false) },
                ).toTypedArray(),
            ).copyInto(image, isolinuxLba * SECTOR_SIZE)
            gtkBytes?.copyInto(image, gtkCfgLba * SECTOR_SIZE)
            spkgtkBytes?.copyInto(image, spkgtkCfgLba * SECTOR_SIZE)
        }
        autoexecBytes?.copyInto(image, autoexecLba * SECTOR_SIZE)
        espImage?.copyInto(image, espLba * SECTOR_SIZE)
        ipxeBinary?.copyInto(image, ipxeLba * SECTOR_SIZE)
        autoInstallerModeBytes?.copyInto(image, autoInstallerModeLba * SECTOR_SIZE)
        return image
    }

    private fun syntheticFatImage(pathName: String, content: String): ByteArray {
        val image = ByteArray(50 * 512)
        image[11] = 0x00
        image[12] = 0x02
        image[13] = 0x01
        image.putLe16(14, 1)
        image[16] = 0x01
        image.putLe16(17, 16)
        image.putLe16(19, 50)
        image[21] = 0xf0.toByte()
        image.putLe16(22, 1)
        image.putLe16(24, 1)
        image.putLe16(26, 1)
        image[510] = 0x55
        image[511] = 0xaa.toByte()
        image[512] = 0xf0.toByte()
        image[513] = 0xff.toByte()
        image[514] = 0xff.toByte()
        image[515] = 0xff.toByte()
        image[516] = 0x0f

        val entryOffset = syntheticFatRootEntryOffset()
        val upperName = pathName.uppercase()
        val shortEntryOffset = if (upperName.isFatShortName()) {
            entryOffset
        } else {
            putSingleLongFileNameEntry(image, entryOffset, pathName)
            entryOffset + 32
        }
        fatShortNameForTest(upperName)
            .toByteArray(Charsets.US_ASCII)
            .copyInto(image, shortEntryOffset)
        image[shortEntryOffset + 11] = 0x20
        image.putLe16(shortEntryOffset + 26, 2)
        val bytes = content.toByteArray(Charsets.UTF_8)
        image.putLe32(shortEntryOffset + 28, bytes.size)
        bytes.copyInto(image, 3 * 512)
        return image
    }

    private fun syntheticFatRootEntryOffset(): Int = 2 * 512

    private fun syntheticFatAutoexecEntryOffset(): Int = syntheticFatRootEntryOffset() + 32

    private fun String.isFatShortName(): Boolean {
        val dot = lastIndexOf('.')
        val base = if (dot < 0) this else substring(0, dot)
        val ext = if (dot < 0) "" else substring(dot + 1)
        return base.isNotBlank() && base.length <= 8 && ext.length <= 3
    }

    private fun fatShortNameForTest(pathName: String): String {
        val dot = pathName.lastIndexOf('.')
        val base = if (dot < 0) pathName else pathName.substring(0, dot)
        val ext = if (dot < 0) "" else pathName.substring(dot + 1)
        return if (pathName.isFatShortName()) {
            base.padEnd(8, ' ') + ext.padEnd(3, ' ')
        } else {
            base.take(6).padEnd(6, '_') + "~1" + ext.take(3).padEnd(3, ' ')
        }
    }

    private fun putSingleLongFileNameEntry(image: ByteArray, offset: Int, name: String) {
        image[offset] = 0x41
        image[offset + 11] = 0x0f
        val chars = name.toCharArray().map { it.code } + 0x0000
        val positions = listOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
        positions.forEachIndexed { index, position ->
            image.putLe16(position + offset, chars.getOrElse(index) { 0xffff })
        }
    }

    private fun primaryVolumeDescriptor(rootLba: Int, rootLength: Int): ByteArray {
        val descriptor = ByteArray(SECTOR_SIZE)
        descriptor[0] = 1
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(descriptor, 1)
        descriptor[6] = 1
        directoryRecord("\u0000", rootLba, rootLength, directory = true).copyInto(descriptor, 156)
        return descriptor
    }

    private fun directorySector(vararg records: ByteArray): ByteArray {
        val sector = ByteArray(SECTOR_SIZE)
        var offset = 0
        records.forEach { record ->
            record.copyInto(sector, offset)
            offset += record.size
        }
        return sector
    }

    private fun directoryRecord(name: String, lba: Int, length: Int, directory: Boolean): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val recordLength = 33 + nameBytes.size + ((nameBytes.size + 1) % 2)
        val record = ByteArray(recordLength)
        record[0] = recordLength.toByte()
        record.putLe32(2, lba)
        record.putLe32(10, length)
        record[25] = if (directory) 0x02 else 0x00
        record[32] = nameBytes.size.toByte()
        nameBytes.copyInto(record, 33)
        return record
    }

    private fun directoryRecordWithRockRidgeName(
        isoName: String,
        rockRidgeName: String,
        lba: Int,
        length: Int,
        directory: Boolean,
    ): ByteArray {
        val nameBytes = isoName.toByteArray(Charsets.US_ASCII)
        val systemUseOffset = 33 + nameBytes.size + if (nameBytes.size % 2 == 0) 1 else 0
        val rockRidgeNameBytes = rockRidgeName.toByteArray(Charsets.UTF_8)
        val nameEntry = ByteArray(5 + rockRidgeNameBytes.size)
        "NM".toByteArray(Charsets.US_ASCII).copyInto(nameEntry)
        nameEntry[2] = nameEntry.size.toByte()
        nameEntry[3] = 1
        nameEntry[4] = 0
        rockRidgeNameBytes.copyInto(nameEntry, 5)
        val unpaddedLength = systemUseOffset + nameEntry.size
        val recordLength = unpaddedLength + (unpaddedLength % 2)
        val record = ByteArray(recordLength)
        record[0] = recordLength.toByte()
        record.putLe32(2, lba)
        record.putLe32(10, length)
        record[25] = if (directory) 0x02 else 0x00
        record[32] = nameBytes.size.toByte()
        nameBytes.copyInto(record, 33)
        nameEntry.copyInto(record, systemUseOffset)
        return record
    }

    private fun ByteArray.putLe32(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
        this[offset + 2] = (value shr 16).toByte()
        this[offset + 3] = (value shr 24).toByte()
    }

    private fun ByteArray.putLe16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }

    private fun ByteArray.readLe32Int(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
    }

    private companion object {
        const val SECTOR_SIZE = 2048
    }
}
