package net.metalstrapper.sparkwriter

import android.content.Context
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.metalstrapper.sparkwriter.agent.SparkPlugDetail
import net.metalstrapper.sparkwriter.agent.SparkPlugDraft
import net.metalstrapper.sparkwriter.agent.SparkPlugFieldValue
import net.metalstrapper.sparkwriter.agent.SparkPlugSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Calls SparkWriter's AppFunctions through the platform, the way an assistant
 * does: find them in the system index, build parameters from the published
 * schema, execute, and read the typed result. An app may call its own functions
 * without EXECUTE_APP_FUNCTIONS, so no agent app is needed.
 *
 * Needs Android 16 (API 36); skipped on older devices.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class AppFunctionsPlatformTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = AppFunctionManager.getInstance(context)
        ?: error("AppFunctions are not supported on this device")

    /**
     * The system indexes the app's functions some time after install. Some builds
     * never index them: the stock API 36 emulator image reports the functions as
     * enabled but returns an empty index, and the androidx client cannot execute a
     * function it cannot find there. Those devices skip these tests rather than fail.
     */
    private fun indexedFunctions(): List<AppFunctionMetadata> = runBlocking {
        assumeTrue("Index already found unavailable in this run", !indexUnavailable)
        val indexed = withTimeoutOrNull(INDEX_WAIT_MS) {
            manager.observeAppFunctions(AppFunctionSearchSpec(packageNames = setOf(context.packageName)))
                .first { packages -> packages.any { it.appFunctions.size >= 3 } }
                .single { it.packageName == context.packageName }
                .appFunctions
        }
        indexUnavailable = indexed == null
        assumeTrue(
            "This device has not indexed SparkWriter's AppFunctions after ${INDEX_WAIT_MS / 1000}s; " +
                "it may not index app functions at all (see tools/agent-test/README.md)",
            indexed != null,
        )
        indexed!!
    }

    private fun function(name: String): AppFunctionMetadata =
        indexedFunctions().single { it.id.endsWith("#$name") }

    private fun execute(function: AppFunctionMetadata, build: AppFunctionData.Builder.() -> Unit = {}): AppFunctionData =
        runBlocking {
            val parameters = AppFunctionData.Builder(function.parameters, function.components).apply(build).build()
            when (val response = manager.executeAppFunction(ExecuteAppFunctionRequest(context.packageName, function.id, parameters))) {
                is ExecuteAppFunctionResponse.Success -> response.returnValue
                is ExecuteAppFunctionResponse.Error -> throw response.error
            }
        }

    @Test
    fun allThreeFunctionsAreIndexedWithDescriptions() {
        val functions = indexedFunctions()

        assertEquals(
            setOf("listSparkPlugs", "describeSparkPlug", "prepareSparkPlugDraft"),
            functions.map { it.id.substringAfter('#') }.toSet(),
        )
        assertTrue(functions.all { it.isEnabled && it.description.isNotBlank() })
    }

    @Test
    fun listAndDescribeThroughThePlatform() {
        val listed = execute(function("listSparkPlugs"))
            .getAppFunctionDataList(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)
            .orEmpty()
            .map { it.deserialize(SparkPlugSummary::class.java) }
        assertTrue(listed.any { it.id == "proxmox-autoinstall" })

        val detail = execute(function("describeSparkPlug")) { setString("sparkPlugId", "proxmox-autoinstall") }
            .getAppFunctionData(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)!!
            .deserialize(SparkPlugDetail::class.java)
        assertTrue(detail.fields.any { it.id == "pve_fqdn" })
    }

    @Test
    fun prepareDraftThroughThePlatformReturnsAnOpenableIntent() {
        val values = listOf(
            SparkPlugFieldValue("pve_fqdn", "pve-lab.home.arpa"),
            SparkPlugFieldValue("pve_timezone", "America/Chicago"),
            SparkPlugFieldValue("pve_network_source", "from-answer"),
            SparkPlugFieldValue("pve_network_cidr", "10.0.0.5/24"),
            SparkPlugFieldValue("pve_network_gateway", "10.0.0.1"),
            SparkPlugFieldValue("pve_network_dns", "10.0.0.1"),
            SparkPlugFieldValue("pve_network_dns", "not-an-ip"),
        )

        val draft = execute(function("prepareSparkPlugDraft")) {
            setString("sparkPlugId", "proxmox-autoinstall")
            setAppFunctionDataList(
                "values",
                values.map { AppFunctionData.serialize(it, SparkPlugFieldValue::class.java) },
            )
        }.getAppFunctionData(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)!!
            .deserialize(SparkPlugDraft::class.java)

        // The later, malformed DNS value replaces the good one, so it is reported and the draft is incomplete.
        assertEquals(listOf("BadFormat"), draft.issues.map { it.code })
        assertEquals(listOf("pve_network_dns"), draft.userMustFill)
        assertEquals(context.packageName, draft.openInSparkWriter.creatorPackage)
    }

    @Test
    fun unknownPlugComesBackAsAnError() {
        try {
            execute(function("describeSparkPlug")) { setString("sparkPlugId", "no-such-plug") }
            fail("Expected an error for an unknown SparkPlug")
        } catch (error: androidx.appfunctions.AppFunctionElementNotFoundException) {
            assertTrue(error.message.orEmpty().contains("listSparkPlugs"))
        }
    }

    private companion object {
        const val INDEX_WAIT_MS = 30_000L

        /** Set after one wait times out, so the other tests skip without waiting again. */
        @Volatile
        var indexUnavailable = false
    }
}
