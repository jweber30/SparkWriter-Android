package net.metalstrapper.sparkwriter.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "installed_manifests")
data class InstalledManifestEntity(
    @PrimaryKey val id: String,
    val name: String,
    val origin: String?,
    val rawJson: String,
    val installedAtEpochMillis: Long,
)

@Entity(tableName = "source_groups")
data class SourceGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "source_group_memberships")
data class SourceGroupMembershipEntity(
    @PrimaryKey val manifestId: String,
    val groupId: String,
)

@Entity(tableName = "receipts")
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val manifestId: String?,
    val sourceId: String?,
    val payloadJson: String,
    val completedAtEpochMillis: Long,
)

@Entity(
    tableName = "iso_index_entries",
    primaryKeys = ["first64KSha256", "sizeBytes", "manifestId"],
)
data class IsoIndexEntryEntity(
    val first64KSha256: String,
    val sizeBytes: Long,
    val pvdSha256: String?,
    val fullSha256: String?,
    val manifestId: String,
    val sourceId: String?,
    val matchKind: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "iso_patch_templates",
    primaryKeys = ["first64KSha256", "sizeBytes", "manifestId", "bootPatchSignature"],
)
data class IsoPatchTemplateEntity(
    val first64KSha256: String,
    val sizeBytes: Long,
    val manifestId: String,
    val bootPatchSignature: String,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Dao
interface InstalledManifestDao {
    @Query("SELECT * FROM installed_manifests ORDER BY name")
    fun observeAll(): Flow<List<InstalledManifestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InstalledManifestEntity)
}

@Dao
interface SourceGroupDao {
    @Query("SELECT * FROM source_groups ORDER BY name COLLATE NOCASE")
    fun observeGroups(): Flow<List<SourceGroupEntity>>

    @Query("SELECT * FROM source_group_memberships")
    fun observeMemberships(): Flow<List<SourceGroupMembershipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: SourceGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMembership(membership: SourceGroupMembershipEntity)

    @Query("DELETE FROM source_group_memberships WHERE manifestId = :manifestId")
    suspend fun clearMembership(manifestId: String)

    @Query("DELETE FROM source_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("DELETE FROM source_group_memberships WHERE groupId = :groupId")
    suspend fun clearGroupMemberships(groupId: String)
}

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM receipts ORDER BY completedAtEpochMillis DESC")
    fun observeAll(): Flow<List<ReceiptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReceiptEntity)
}

@Dao
interface IsoIndexDao {
    @Query("SELECT * FROM iso_index_entries WHERE first64KSha256 = :first64KSha256 AND sizeBytes = :sizeBytes ORDER BY updatedAtEpochMillis DESC")
    suspend fun matches(first64KSha256: String, sizeBytes: Long): List<IsoIndexEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IsoIndexEntryEntity)

    @Query("DELETE FROM iso_index_entries WHERE matchKind = 'declared'")
    suspend fun clearDeclared()
}

@Dao
interface IsoPatchTemplateDao {
    @Query(
        "SELECT * FROM iso_patch_templates WHERE first64KSha256 = :first64KSha256 AND sizeBytes = :sizeBytes AND manifestId = :manifestId AND bootPatchSignature = :bootPatchSignature LIMIT 1",
    )
    suspend fun get(
        first64KSha256: String,
        sizeBytes: Long,
        manifestId: String,
        bootPatchSignature: String,
    ): IsoPatchTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IsoPatchTemplateEntity)
}

@Database(
    entities = [
        InstalledManifestEntity::class,
        ReceiptEntity::class,
        IsoIndexEntryEntity::class,
        IsoPatchTemplateEntity::class,
        SourceGroupEntity::class,
        SourceGroupMembershipEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class SparkWriterDatabase : RoomDatabase() {
    abstract fun manifests(): InstalledManifestDao
    abstract fun receipts(): ReceiptDao
    abstract fun isoIndex(): IsoIndexDao
    abstract fun isoPatchTemplates(): IsoPatchTemplateDao
    abstract fun sourceGroups(): SourceGroupDao

    companion object {
        @Volatile
        private var instance: SparkWriterDatabase? = null
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS iso_index_entries (
                        first64KSha256 TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        pvdSha256 TEXT,
                        fullSha256 TEXT,
                        manifestId TEXT NOT NULL,
                        sourceId TEXT,
                        matchKind TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(first64KSha256, sizeBytes, manifestId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS iso_patch_templates (
                        first64KSha256 TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        manifestId TEXT NOT NULL,
                        bootPatchSignature TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(first64KSha256, sizeBytes, manifestId, bootPatchSignature)
                    )
                    """.trimIndent(),
                )
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS source_groups (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS source_group_memberships (manifestId TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL)")
            }
        }

        fun open(context: Context): SparkWriterDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SparkWriterDatabase::class.java,
                    "sparkwriter.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
        }
    }
}
