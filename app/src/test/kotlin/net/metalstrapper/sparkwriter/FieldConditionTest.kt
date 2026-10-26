package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.FieldCondition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One evaluator serves the wizard, return actions, and auxiliary-data actions; pin its rules. */
class FieldConditionTest {
    @Test
    fun missingConditionAlwaysMatches() {
        val condition: FieldCondition? = null
        assertTrue(condition.matches(emptyMap()))
    }

    @Test
    fun equalsAndNotEquals() {
        val values = mapOf("pve_disk_filesystem" to "zfs")
        assertTrue(FieldCondition("pve_disk_filesystem", "equals", "zfs").matches(values))
        assertFalse(FieldCondition("pve_disk_filesystem", "equals", "ext4").matches(values))
        assertTrue(FieldCondition("pve_disk_filesystem", "not_equals", "ext4").matches(values))
    }

    @Test
    fun missingFieldComparesAsEmpty() {
        assertFalse(FieldCondition("pve_network_source", "equals", "from-answer").matches(emptyMap()))
        assertTrue(FieldCondition("pve_network_source", "empty", "").matches(emptyMap()))
    }

    @Test
    fun dashAndUnderscoreFieldIdsAreEquivalent() {
        assertTrue(FieldCondition("network_source", "equals", "dhcp").matches(mapOf("network-source" to "dhcp")))
        assertTrue(FieldCondition("network-source", "equals", "dhcp").matches(mapOf("network_source" to "dhcp")))
    }

    @Test
    fun containsChecksLinesAndSubstrings() {
        val values = mapOf("ssh_keys" to "ssh-ed25519 AAAA one\nssh-rsa BBBB two")
        assertTrue(FieldCondition("ssh_keys", "contains", "ssh-rsa BBBB two").matches(values))
        assertTrue(FieldCondition("ssh_keys", "contains", "ed25519").matches(values))
        assertFalse(FieldCondition("ssh_keys", "not_contains", "ed25519").matches(values))
        assertTrue(FieldCondition("ssh_keys", "not_empty", "").matches(values))
    }

    @Test
    fun unknownOperatorMatches() {
        assertTrue(FieldCondition("anything", "regex", ".*").matches(emptyMap()))
    }
}
