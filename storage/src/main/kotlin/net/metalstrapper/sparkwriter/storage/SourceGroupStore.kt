package net.metalstrapper.sparkwriter.storage

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

data class SourceGroup(val id: String, val name: String)

data class SourceGrouping(
    val groups: List<SourceGroup> = emptyList(),
    val manifestGroups: Map<String, String> = emptyMap(),
)

class SourceGroupStore(context: Context) {
    private val dao = SparkWriterDatabase.open(context.applicationContext).sourceGroups()

    fun observe(): Flow<SourceGrouping> = combine(dao.observeGroups(), dao.observeMemberships()) { groups, memberships ->
        SourceGrouping(
            groups = groups.map { SourceGroup(it.id, it.name) },
            manifestGroups = memberships.associate { it.manifestId to it.groupId },
        )
    }

    suspend fun create(name: String) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Group name cannot be empty" }
        dao.upsertGroup(SourceGroupEntity(UUID.randomUUID().toString(), cleanName, System.currentTimeMillis()))
    }

    suspend fun assign(manifestId: String, groupId: String?) {
        if (groupId == null) dao.clearMembership(manifestId)
        else dao.setMembership(SourceGroupMembershipEntity(manifestId, groupId))
    }

    suspend fun delete(groupId: String) {
        dao.clearGroupMemberships(groupId)
        dao.deleteGroup(groupId)
    }
}
