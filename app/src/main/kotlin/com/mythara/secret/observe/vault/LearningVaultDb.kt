package com.mythara.secret.observe.vault

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mythara.memory.graph.GraphEdge
import com.mythara.memory.graph.GraphEntity
import com.mythara.memory.graph.GraphMemoryDao
import com.mythara.secret.observe.speaker.EnrolledSpeaker
import com.mythara.secret.observe.speaker.SpeakerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        LearningEntity::class,
        EnrolledSpeaker::class,
        GraphEntity::class,
        GraphEdge::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class LearningVaultDb : RoomDatabase() {
    abstract fun learnings(): LearningDao
    abstract fun speakers(): SpeakerDao
    abstract fun graph(): GraphMemoryDao

    companion object {
        const val DATABASE_NAME = "mythara_learning_vault.db"

        /**
         * v1 → v2: add the `enrolled_speakers` table for M8.4 speaker
         * identification. Adds a new table only — existing learnings
         * survive the migration, which matters because real users
         * already have weeks of Observe transcripts in there.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `enrolled_speakers` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `ref_vector_bytes` BLOB NOT NULL,
                        `ref_vector_dim` INTEGER NOT NULL,
                        `enrolled_at_ms` INTEGER NOT NULL,
                        `last_matched_at_ms` INTEGER NOT NULL,
                        `match_count` INTEGER NOT NULL,
                        `enrollment_sample_count` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_enrolled_speakers_name` ON `enrolled_speakers` (`name`)",
                )
            }
        }

        /**
         * v2 → v3: add the temporal-knowledge-graph tables for the
         * Graphiti-inspired agent memory. Two new tables (entities
         * + edges) with their indexes; existing learnings + speakers
         * survive untouched.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `graph_entities` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `kind` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `nameKey` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `attrsJson` TEXT,
                        `conf` REAL NOT NULL,
                        `synced` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_entities_kind` ON `graph_entities` (`kind`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_graph_entities_nameKey` ON `graph_entities` (`nameKey`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `graph_edges` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `subjectId` TEXT NOT NULL,
                        `predicate` TEXT NOT NULL,
                        `objectId` TEXT NOT NULL,
                        `validAtMs` INTEGER NOT NULL,
                        `validUntilMs` INTEGER,
                        `createdAtMs` INTEGER NOT NULL,
                        `factText` TEXT,
                        `conf` REAL NOT NULL,
                        `synced` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_edges_subjectId_predicate` ON `graph_edges` (`subjectId`, `predicate`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_edges_objectId` ON `graph_edges` (`objectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_edges_createdAtMs` ON `graph_edges` (`createdAtMs`)")
            }
        }
    }
}

/**
 * Hilt wiring for the vault. v1 uses plain Room (no SQLCipher); the
 * database file lives in the app's private data directory which is
 * already sandboxed by Android. SQLCipher will land in a future
 * milestone once the threat model warrants it.
 */
@Module
@InstallIn(SingletonComponent::class)
object LearningVaultModule {
    @Provides
    @Singleton
    fun provideLearningVaultDb(@ApplicationContext ctx: Context): LearningVaultDb =
        Room.databaseBuilder(ctx, LearningVaultDb::class.java, LearningVaultDb.DATABASE_NAME)
            .addMigrations(LearningVaultDb.MIGRATION_1_2, LearningVaultDb.MIGRATION_2_3)
            // Belt-and-braces: if a hand-rolled migration ever lands in a
            // future version-bump and trips over a corner case, drop and
            // recreate. Acceptable pre-public-release.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideLearningDao(db: LearningVaultDb): LearningDao = db.learnings()

    @Provides
    @Singleton
    fun provideSpeakerDao(db: LearningVaultDb): SpeakerDao = db.speakers()

    @Provides
    @Singleton
    fun provideGraphMemoryDao(db: LearningVaultDb): GraphMemoryDao = db.graph()
}
