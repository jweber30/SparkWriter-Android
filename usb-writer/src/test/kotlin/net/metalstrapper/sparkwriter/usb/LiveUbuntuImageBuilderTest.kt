package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.AuxiliaryDataFile
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.BootKernelArgPatchSpec
import net.metalstrapper.sparkwriter.core.BootKernelArgSelector
import net.metalstrapper.sparkwriter.core.BootPatchPlan
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class LiveUbuntuImageBuilderTest {
    @Test
    fun buildSparkWriterStyleUbuntuAutoinstallImageForQemu() {
        assumeTrue(System.getenv("LIVE_QEMU_IMAGE_BUILDER") == "1")
        val sourceIso = File(
            System.getenv("LIVE_QEMU_SOURCE_ISO")
                ?: "${System.getProperty("user.home")}/ubuntu-26.04-live-server-amd64.iso",
        )
        val outputDir = File(System.getenv("LIVE_QEMU_OUTPUT_DIR") ?: "build/live-qemu").apply { mkdirs() }
        val outputImage = File(outputDir, "ubuntu-sparkwriter-live.raw")
        val minExtraBytes = 256L * 1024L * 1024L
        val deviceBytes = alignUp(sourceIso.length() + minExtraBytes, 1024L * 1024L)
        val sourceSha256 = sourceIso.sha256()

        RandomAccessFile(outputImage, "rw").use { out ->
            out.setLength(0)
            RandomAccessFile(sourceIso, "r").use { input ->
                input.copyTo(out)
            }
            out.setLength(deviceBytes)

            val bootPatches = IsoBootPatchWriter.buildPatches(ubuntuBootPatchPlan()) { offset, length ->
                out.readByteRange(offset, length)
            }
            bootPatches.forEach { patch ->
                out.seek(patch.offset)
                out.write(patch.bytes)
            }

            val geometry = GptPartitionAppender.appendFat32Partition(
                label = "CIDATA",
                sizeBytes = 64L * 1024L * 1024L,
                sourceSha256 = sourceSha256,
                deviceBytes = deviceBytes,
                readBytes = { offset, length -> out.readByteRange(offset, length) },
                writeBytes = { offset, data ->
                    out.seek(offset)
                    out.write(data)
                },
            )
            val cidata = VFatImage.sparse(ubuntuAuxiliaryPlan(sourceSha256).copy(geometry = geometry))
            var written = 0
            while (written < cidata.size) {
                val length = minOf(64 * 1024, cidata.size - written)
                out.seek(geometry.byteOffset + written)
                out.write(cidata.chunk(written, length))
                written += length
            }
        }

        File(outputDir, "README.txt").writeText(
            """
            Built by LiveUbuntuImageBuilderTest.
            source=${sourceIso.absolutePath}
            source_sha256=$sourceSha256
            image=${outputImage.absolutePath}
            image_bytes=${outputImage.length()}
            apt_proxy=http://apt-proxy.srv.home.arpa:3142
            """.trimIndent() + "\n",
        )
    }
}

private fun ubuntuBootPatchPlan(): BootPatchPlan = BootPatchPlan(
    filesystem = "iso9660",
    kernelArgPatches = listOf(
        BootKernelArgPatchSpec(
            candidatePaths = listOf("/boot/grub/grub.cfg"),
            configFamily = "grub",
            selector = BootKernelArgSelector(
                menuentry = "Try or Install Ubuntu",
                kernel = "/casper/vmlinuz",
            ),
            ensureArgs = listOf("autoinstall", "ds=nocloud", "console=ttyS0,115200n8"),
            insertBefore = "---",
        ),
        BootKernelArgPatchSpec(
            candidatePaths = listOf("/boot/grub/grub.cfg"),
            configFamily = "grub",
            selector = BootKernelArgSelector(
                menuentry = "Ubuntu (safe graphics)",
                kernel = "/casper/vmlinuz",
            ),
            ensureArgs = listOf("autoinstall", "ds=nocloud", "console=ttyS0,115200n8"),
            insertBefore = "---",
        ),
    ),
)

private fun ubuntuAuxiliaryPlan(sourceSha256: String): AuxiliaryDataPlan = AuxiliaryDataPlan(
    label = "CIDATA",
    filesystem = "vfat",
    placement = "append_after_image",
    sizeBytes = 64L * 1024L * 1024L,
    sourceSha256 = sourceSha256,
    files = listOf(
        AuxiliaryDataFile(
            path = "/user-data",
            mediaType = "text/cloud-config",
            content = """
                #cloud-config
                autoinstall:
                  version: 1
                  apt:
                    proxy: "http://apt-proxy.srv.home.arpa:3142"
                  identity:
                    hostname: "sparkwriter-qemu"
                    username: "ubuntu"
                    password: "${"$"}6${"$"}nrlLcG2cx5xOjt1p${"$"}YHedNEzo1adLykz4wbM5QCPhdROHVv.k5XbYOMfXo2Fc5pJ/uwi9sDIq45mvtyrCu9Lx/CO.tIN0ClebuEsSV/"
                  ssh:
                    install-server: true
                    allow-pw: true
                  storage:
                    layout:
                      name: direct
            """.trimIndent() + "\n",
        ),
        AuxiliaryDataFile(
            path = "/meta-data",
            mediaType = "text/plain",
            content = "instance-id: sparkwriter-qemu\nlocal-hostname: sparkwriter-qemu\n",
        ),
    ),
)

private fun RandomAccessFile.copyTo(out: RandomAccessFile) {
    val buffer = ByteArray(1024 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return
        out.write(buffer, 0, read)
    }
}

private fun RandomAccessFile.readByteRange(offset: Long, length: Int): ByteArray {
    val bytes = ByteArray(length)
    seek(offset)
    var complete = 0
    while (complete < length) {
        val read = read(bytes, complete, length - complete)
        if (read < 0) break
        complete += read
    }
    return bytes
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun alignUp(value: Long, alignment: Long): Long {
    val remainder = value % alignment
    return if (remainder == 0L) value else value + alignment - remainder
}
