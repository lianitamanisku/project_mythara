package com.mythara.analytics

import android.content.Context
import androidx.room.ColumnInfo
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-contact aggregated profile, persisted so the analytics screen
 * can render fast without re-running Gemma every time the user opens
 * it. Re-built periodically (or on demand) by [ContactAnalyticsBuilder]
 * from raw vault entries facetted with `contact:<name>`.
 *
 * The fields divide into:
 *   • Identity — display name + canonical key, phone if known,
 *     favorite + tone status mirrored from FavoritesStore.
 *   • Activity — first / last interaction, total counts.
 *   • Relationship signal — running summary paragraph + free-form
 *     notable traits, both produced by the local Gemma model from
 *     concatenated vault content for that contact.
 *   • Big Five personality — five 0–1 scores estimated by Gemma over
 *     the same content. Updated only when the sample size is large
 *     enough to be meaningful (>= [MIN_BIG_FIVE_SAMPLE] vault rows).
 *
 * "Personality of THIS contact, as Mythara observes them through
 * their messages with the user" — not a clinical assessment, just
 * an LLM-estimated read for response tuning. Surfaced honestly in
 * the UI as "Lumi's read on this person."
 */
@Entity(tableName = "contact_profiles")
data class ContactProfileRow(
    /** Lowercase, trimmed; the canonical lookup key. */
    @PrimaryKey @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val phone: String? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "tone_label") val toneLabel: String? = null,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
    @ColumnInfo(name = "last_interaction_ms") val lastInteractionMs: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int = 0,
    @ColumnInfo(name = "image_count") val imageCount: Int = 0,
    /** JSON array of strings — durable topics ("hiking", "python", "family"). */
    @ColumnInfo(name = "top_topics_json") val topTopicsJson: String = "[]",
    /** Free-form paragraph; how the user relates to / talks with this person. */
    @ColumnInfo(name = "relationship_summary") val relationshipSummary: String? = null,
    /** 0..1 Big Five scores. Null when sample size is too small to estimate. */
    val openness: Double? = null,
    val conscientiousness: Double? = null,
    val extraversion: Double? = null,
    val agreeableness: Double? = null,
    val neuroticism: Double? = null,
    @ColumnInfo(name = "big_five_sample_size") val bigFiveSampleSize: Int = 0,
    @ColumnInfo(name = "big_five_last_updated_ms") val bigFiveLastUpdatedMs: Long? = null,
    /** JSON array of strings — short observed traits beyond Big Five. */
    @ColumnInfo(name = "notable_traits_json") val notableTraitsJson: String = "[]",
    /**
     * JSON array of strings — short, actionable "key points to note"
     * surfaced at the top of the contact detail screen. Things the
     * user would want remembered before their NEXT conversation with
     * this person: recent life events, upcoming dates, sensitive
     * topics, open threads / promises, recurring concerns. Generated
     * by Gemma over the contact's vault content.
     *
     * Distinct from notable_traits which describes WHO the person is;
     * key_points describes WHAT'S HAPPENING in their life right now.
     */
    @ColumnInfo(name = "key_points_json") val keyPointsJson: String = "[]",
    /**
     * Free-form user-authored notes about this contact. Always
     * preserved across rebuilds — Gemma never overwrites this field.
     * Surfaced AT THE TOP of the detail screen and injected
     * prominently into the auto-reply prompt as authoritative
     * context (user-written facts override LLM inferences).
     *
     * Use cases: corrections ("she's allergic to nuts — important"),
     * additional context the chat history can't have learned
     * ("knows her from college, decade-long friendship"),
     * explicit reminders ("don't bring up her brother").
     */
    @ColumnInfo(name = "user_notes") val userNotes: String? = null,
    /**
     * Gemma-generated actionable communication summary derived from
     * Big Five + notable traits. A short paragraph in the form
     * "How to message <name>: keep replies …; lean into …; avoid …".
     * Distinct from relationship_summary (which describes the
     * relationship) and notable_traits (which lists adjectives) —
     * this synthesises Big Five scores into concrete messaging
     * guidance that the auto-reply prompt can use directly.
     *
     * Null when sample size is below threshold or Gemma isn't loaded.
     * Repopulated on every successful Big Five inference pass.
     */
    @ColumnInfo(name = "personality_insights") val personalityInsights: String? = null,
    /**
     * Absolute path of an app-side avatar override the user picked
     * inside Mythara (stored under filesDir/contact_photos/). Purely
     * local — never written back to the phone's address book. Null =
     * fall back to the phone contact's photo, then the initial-letter
     * avatar. Preserved across rebuilds like [userNotes].
     */
    @ColumnInfo(name = "photo_uri") val photoUri: String? = null,
    /**
     * JSON array of strings — alternate names this person is known
     * as across apps ("Johnny" in WhatsApp, "John S." in Teams,
     * "@johnsmith" on Slack). Used by the cross-app person matcher
     * to dedupe a single human across multiple app channels +
     * surfaced in the People row as "found in: …" badges.
     * Empty array when only one name has ever been seen.
     */
    @ColumnInfo(name = "aliases_json", defaultValue = "[]")
    val aliasesJson: String = "[]",
    /**
     * JSON array of strings — package names of apps Mythara has
     * seen this person interact through (Teams, WhatsApp, SMS,
     * Slack, etc.). Drives the small "found in" badge cluster
     * under each People row + lets the People list explain WHY
     * a row exists when it was auto-added from a notification.
     */
    @ColumnInfo(name = "source_apps_json", defaultValue = "[]")
    val sourceAppsJson: String = "[]",
    /**
     * True when this profile was auto-created by the cross-app
     * notification observer (versus appearing because the user
     * exchanged messages via the agent's auto-reply path). Lets
     * the UI render auto-added rows below favourites in a distinct
     * "auto-discovered" section the user can promote / dismiss.
     */
    @ColumnInfo(name = "is_auto_added", defaultValue = "0")
    val isAutoAdded: Boolean = false,
    /** Last time the analytics builder produced / updated this row. */
    @ColumnInfo(name = "last_built_ms") val lastBuiltMs: Long = 0,
    /**
     * Type-aware entity classification. Set by
     * [com.mythara.analytics.EntityKindClassifier] either at insert
     * time (CrossAppPersonObserver) or retroactively by
     * [com.mythara.analytics.PeopleCleanupRunner]. The People screen
     * only renders rows whose kind == "person" AND is_hidden == false;
     * the Insights graph paints nodes by kind. Allowed:
     *   person, place, organization, app, notification-source, unknown.
     * Defaults to `unknown` for rows that pre-date the v7 migration
     * so the cleanup pass can re-classify them.
     */
    @ColumnInfo(name = "kind", defaultValue = "unknown")
    val kind: String = KIND_UNKNOWN,
    /** Classifier confidence 0..1. Below ~0.7 flags the row for
     *  user review on first People-screen open. */
    @ColumnInfo(name = "kind_confidence", defaultValue = "0.5")
    val kindConfidence: Float = 0.5f,
    /** When the classifier last assigned the kind. Null = never;
     *  the cleanup runner picks these up first. */
    @ColumnInfo(name = "kind_classified_at_ms")
    val kindClassifiedAtMs: Long? = null,
    /**
     * Soft-hide flag. When true, the row stays in the database
     * (so memory sync + history are preserved) but disappears from
     * the People-list rendering + tappable entry points. The
     * cleanup runner flips this true for every classified non-person.
     * A "Hidden Rows" sub-screen renders these with one-tap restore.
     */
    @ColumnInfo(name = "is_hidden", defaultValue = "0")
    val isHidden: Boolean = false,
) {
    companion object {
        const val MIN_BIG_FIVE_SAMPLE = 6

        // Canonical kind values — keep this list in sync with
        // EntityKindClassifier + InsightsScreen.nodeColorForKind.
        const val KIND_PERSON = "person"
        const val KIND_PLACE = "place"
        const val KIND_ORG = "organization"
        const val KIND_APP = "app"
        const val KIND_NOTIFICATION = "notification-source"
        const val KIND_UNKNOWN = "unknown"
        val ALL_KINDS = setOf(KIND_PERSON, KIND_PLACE, KIND_ORG, KIND_APP, KIND_NOTIFICATION, KIND_UNKNOWN)
    }
}

@Dao
interface ContactProfileDao {
    @Query(
        "SELECT * FROM contact_profiles ORDER BY is_favorite DESC, last_interaction_ms DESC",
    )
    fun observeAll(): Flow<List<ContactProfileRow>>

    @Query(
        "SELECT * FROM contact_profiles ORDER BY is_favorite DESC, last_interaction_ms DESC",
    )
    suspend fun listAll(): List<ContactProfileRow>

    @Query("SELECT * FROM contact_profiles WHERE name_key = :key LIMIT 1")
    suspend fun byKey(key: String): ContactProfileRow?

    /** Substring scan of the JSON aliases column. Used by the
     *  cross-app person matcher to find the canonical contact for
     *  an incoming sender name when the direct name-key lookup
     *  missed (e.g. notification says "Rose" but the canonical
     *  contact is `roselyn-mathew` with "Rose" in its aliases).
     *  Caller is responsible for filtering false positives by
     *  re-checking the JSON list. */
    @Query(
        """
        SELECT * FROM contact_profiles
        WHERE aliases_json LIKE '%' || :needle || '%'
        ORDER BY message_count DESC
        LIMIT 10
        """,
    )
    suspend fun findByAliasContaining(needle: String): List<ContactProfileRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ContactProfileRow)

    /**
     * Update only the user-authored notes without touching anything
     * else. Avoids racing the analytics builder when the user edits
     * notes mid-rebuild — partial updates beat the full-row replace
     * the upsert path uses.
     */
    @Query("UPDATE contact_profiles SET user_notes = :notes WHERE name_key = :key")
    suspend fun updateUserNotes(key: String, notes: String?)

    /**
     * Update only the app-side avatar override path. Partial update —
     * like [updateUserNotes], avoids racing the analytics builder's
     * full-row upsert.
     */
    @Query("UPDATE contact_profiles SET photo_uri = :uri WHERE name_key = :key")
    suspend fun updatePhotoUri(key: String, uri: String?)

    /** Replace the cross-app aliases list (JSON array). */
    @Query("UPDATE contact_profiles SET aliases_json = :json WHERE name_key = :key")
    suspend fun updateAliases(key: String, json: String)

    /**
     * Partial update of just the favorite flag. Used by the contact-
     * detail star toggle so the People list reorders immediately
     * without waiting for the next analytics rebuild. Pairs with a
     * FavoritesStore write so the canonical curated allow-list also
     * picks up the change.
     */
    @Query("UPDATE contact_profiles SET is_favorite = :fav WHERE name_key = :key")
    suspend fun updateIsFavorite(key: String, fav: Boolean)

    /** Replace the source-apps list (JSON array of package names). */
    @Query("UPDATE contact_profiles SET source_apps_json = :json WHERE name_key = :key")
    suspend fun updateSourceApps(key: String, json: String)

    /** Set the classification result for a single row. Used by both
     *  [com.mythara.people.CrossAppPersonObserver] at insert time and
     *  the bulk [com.mythara.analytics.PeopleCleanupRunner] pass. */
    @Query(
        """
        UPDATE contact_profiles
        SET kind = :kind,
            kind_confidence = :conf,
            kind_classified_at_ms = :tsMs,
            is_hidden = :hidden
        WHERE name_key = :key
        """,
    )
    suspend fun updateKind(key: String, kind: String, conf: Float, tsMs: Long, hidden: Boolean)

    /** Restore a previously-hidden row. Flips `is_hidden` back to
     *  false and stamps it as `kind = "person"` so the user's manual
     *  override beats the classifier's verdict on the next pass. */
    @Query(
        """
        UPDATE contact_profiles
        SET is_hidden = 0,
            kind = 'person',
            kind_confidence = 1.0,
            kind_classified_at_ms = :tsMs
        WHERE name_key = :key
        """,
    )
    suspend fun restoreAsPerson(key: String, tsMs: Long)

    /** Bulk-set every classifier field back to defaults so the
     *  cleanup runner re-processes the whole table. Surfaced via a
     *  hidden "force re-classify all" action in Settings. */
    @Query(
        """
        UPDATE contact_profiles
        SET kind = 'unknown',
            kind_confidence = 0.5,
            kind_classified_at_ms = NULL,
            is_hidden = 0
        """,
    )
    suspend fun resetAllKinds(): Int

    /** All rows that have NEVER been classified — the bulk runner
     *  prioritises these on its first pass. */
    @Query(
        "SELECT * FROM contact_profiles WHERE kind_classified_at_ms IS NULL",
    )
    suspend fun listUnclassified(): List<ContactProfileRow>

    /** All currently-hidden rows for the "Hidden non-people"
     *  Settings sub-screen. */
    @Query(
        "SELECT * FROM contact_profiles WHERE is_hidden = 1 ORDER BY last_interaction_ms DESC",
    )
    fun observeHidden(): Flow<List<ContactProfileRow>>

    /** Promote an auto-added row out of the "auto-discovered"
     *  section (e.g. user opens it + interacts → no longer flagged). */
    @Query("UPDATE contact_profiles SET is_auto_added = 0 WHERE name_key = :key")
    suspend fun clearAutoAdded(key: String)

    @Query("DELETE FROM contact_profiles WHERE name_key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM contact_profiles")
    suspend fun clear()
}

@Database(entities = [ContactProfileRow::class], version = 7, exportSchema = false)
abstract class ContactProfilesDb : RoomDatabase() {
    abstract fun profiles(): ContactProfileDao
}

/** v4 → v5: adds the nullable `photo_uri` app-override column. A real
 *  migration (not destructive fallback) so user-authored `user_notes`
 *  survive the schema bump. */
internal val MIGRATION_CONTACTS_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact_profiles ADD COLUMN photo_uri TEXT")
    }
}

/** v5 → v6: cross-app person identity unification. Adds:
 *    - aliases_json:    JSON array of alternate names (other apps)
 *    - source_apps_json: JSON array of package names where seen
 *    - is_auto_added:   true when auto-created by the notification
 *                       observer (vs added through agent interaction)
 *  All three carry default values so existing rows survive the bump. */
internal val MIGRATION_CONTACTS_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact_profiles ADD COLUMN aliases_json TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE contact_profiles ADD COLUMN source_apps_json TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE contact_profiles ADD COLUMN is_auto_added INTEGER NOT NULL DEFAULT 0")
    }
}

/** v6 → v7: typed entities + soft-hide. Adds:
 *    - kind:                   entity-type classification (defaults to "unknown")
 *    - kind_confidence:        classifier confidence 0..1
 *    - kind_classified_at_ms:  when the classifier last ran (null = never)
 *    - is_hidden:              soft-hide flag (default false)
 *  All defaulted so existing rows survive cleanly. The
 *  PeopleCleanupRunner re-classifies unknown rows on first run. */
internal val MIGRATION_CONTACTS_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact_profiles ADD COLUMN kind TEXT NOT NULL DEFAULT 'unknown'")
        db.execSQL("ALTER TABLE contact_profiles ADD COLUMN kind_confidence REAL NOT NULL DEFAULT 0.5")
        db.execSQL("ALTER TABLE contact_profiles ADD COLUMN kind_classified_at_ms INTEGER")
        db.execSQL("ALTER TABLE contact_profiles ADD COLUMN is_hidden INTEGER NOT NULL DEFAULT 0")
    }
}

@Singleton
class ContactProfileRepository @Inject constructor(@ApplicationContext ctx: Context) {
    private val db: ContactProfilesDb =
        Room.databaseBuilder(ctx, ContactProfilesDb::class.java, "mythara_contact_profiles.db")
            .addMigrations(MIGRATION_CONTACTS_4_5, MIGRATION_CONTACTS_5_6, MIGRATION_CONTACTS_6_7)
            .fallbackToDestructiveMigration()
            .build()
    val dao: ContactProfileDao = db.profiles()
}
