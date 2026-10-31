package net.metalstrapper.sparkwriter

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appfunctions.AppFunctionContext
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import net.metalstrapper.sparkwriter.agent.SparkPlugFieldValue
import net.metalstrapper.sparkwriter.agent.SparkWriterAgentFunctions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The hand-off an assistant relies on: a draft opens the wizard on the right step,
 * with the values filled in, and nothing is written.
 */
@RunWith(AndroidJUnit4::class)
class DraftHandoffTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val compose = createEmptyComposeRule()

    // Skips the notification explanation dialog so it does not cover the wizard.
    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                GrantPermissionRule.grant()
            },
        )
        .around(compose)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeActivity() {
        scenario?.close()
    }

    private val proxmoxComplete = mapOf(
        "pve_fqdn" to "pve-lab.home.arpa",
        "pve_timezone" to "America/Chicago",
        "pve_network_source" to "from-answer",
        "pve_network_cidr" to "10.0.0.5/24",
        "pve_network_gateway" to "10.0.0.1",
        "pve_network_dns" to "10.0.0.1",
    )

    private fun launchDraft(values: Map<String, String>) {
        val uri = buildDraftUri(ConfigDraftRequest("proxmox-autoinstall", values))
        scenario = ActivityScenario.launch(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java),
        )
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private val prefilledFromDraft: String
        get() = context.getString(R.string.prefilled_from, context.getString(R.string.draft_source_label))

    @Test
    fun completeDraftOpensReviewWithValuesMarked() {
        launchDraft(proxmoxComplete)

        waitForText(prefilledFromDraft)
        compose.onAllNodesWithText(context.getString(R.string.review)).onFirst().assertIsDisplayed()
        // Review is a scrolling list; later values exist but may be below the fold.
        compose.onAllNodesWithText("pve-lab.home.arpa").onFirst().assertExists()
        compose.onAllNodesWithText("10.0.0.5/24").onFirst().assertExists()
    }

    @Test
    fun draftMissingARequiredFieldOpensThatPage() {
        launchDraft(proxmoxComplete - "pve_timezone")

        waitForText("Timezone")
        // Identity is the page with the blank Timezone; Review is not shown.
        compose.onAllNodesWithText("Identity").onFirst().assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText(context.getString(R.string.review)).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun agentFunctionsDescribeAndPrepareTheBundledPlug() = runBlocking<Unit> {
        val functions = SparkWriterAgentFunctions()
        val appFunctionContext = object : AppFunctionContext {
            override val context: Context = this@DraftHandoffTest.context
        }

        val plugs = functions.listSparkPlugs(appFunctionContext)
        assertTrue(plugs.any { it.id == "proxmox-autoinstall" })

        val detail = functions.describeSparkPlug(appFunctionContext, "proxmox-autoinstall")
        assertTrue(detail.fields.any { it.id == "pve_network_cidr" })

        val draft = functions.prepareSparkPlugDraft(
            appFunctionContext,
            "proxmox-autoinstall",
            proxmoxComplete.map { (id, value) -> SparkPlugFieldValue(id, value) } +
                SparkPlugFieldValue("pve_network_source_typo", "x"),
        )
        assertTrue(draft.complete)
        assertEquals(listOf("pve_network_source_typo"), draft.issues.map { it.fieldId })
        assertFalse("pve_network_source_typo" in draft.appliedFieldIds)

        // What an assistant does next: open SparkWriter with the returned intent.
        draft.openInSparkWriter.send()
        waitForText(prefilledFromDraft)
        compose.onAllNodesWithText(context.getString(R.string.review)).onFirst().assertIsDisplayed()
    }

    @Test
    fun unknownPlugIsReportedToTheAssistant() = runBlocking<Unit> {
        val appFunctionContext = object : AppFunctionContext {
            override val context: Context = this@DraftHandoffTest.context
        }
        val error = runCatching {
            SparkWriterAgentFunctions().describeSparkPlug(appFunctionContext, "no-such-plug")
        }.exceptionOrNull()

        assertTrue(error is androidx.appfunctions.AppFunctionElementNotFoundException)
    }
}
