package com.mythara.imports

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar

/**
 * Reduces a batch of imported [MessageRecord]s (SMS provider scan,
 * WhatsApp `.txt` export, future Signal / Telegram backups) into a
 * handful of persona-trait vault records.
 *
 * Privacy posture: we don't persist raw messages. Only the
 * extracted patterns — "top texting contact is Mom", "most active
 * texting hour is 9-10pm", "common phrases: lol / thanks / on my
 * way" — land in the vault. Those records sync to GitHub like the
 * usage-stats persona records.
 *
 * Two layers run in sequence:
 *   1. Cheap heuristics over the entire batch — top contacts, peak-hour
 *      band, style heuristics, totals. These always run and don't need
 *      Gemma to be loaded.
 *   2. If Gemma is available, a sampled chunk of the user's outgoing
 *      messages (the user's own voice, never inbound from other people)
 *      gets fed through the on-device LLM extractor to lift deeper
 *      traits — recurring topics, relationship dynamics, voice/tone
 *      patterns. This is the "way of communicating" pass the user
 *      explicitly asked for.
 *
 * Privacy posture: we don't persist raw messages. Only the extracted
 * traits — "top texting contact is Mom", "talks about gym a lot", "uses
 * dry humour" — land in the vault. Those records sync to GitHub like
 * the usage-stats persona records. Gemma runs ON-DEVICE; the messages
 * never leave the phone, even to MiniMax.
 */
@Singleton
class MessagePersonaExtractor @Inject constructor(
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
    private val gemma: GemmaExtractor,
) {
    data class Report(
        val ok: Boolean,
        val recordsWritten: Int,
        val messagesAnalyzed: Int,
        val message: String? = null,
    )

    suspend fun extractAndPersist(
        source: String,
        messages: List<MessageRecord>,
    ): Report {
        if (messages.isEmpty()) {
            return Report(false, 0, 0, "no messages to analyse")
        }
        val now = System.currentTimeMillis()
        var written = 0

        // 1) Top contacts. Count every message whose `contact` field
        //    is populated, regardless of direction — for WhatsApp
        //    exports the user-sent rows have `contact=null` (only the
        //    other party's name is in the export), so a user-only
        //    grouping comes back empty and the analytics path never
        //    learns about anyone. Counting incoming + outgoing-with-
        //    known-contact handles SMS (where both sides carry a
        //    contact) and WhatsApp (where only incoming does).
        val userMessages = messages.filter { it.isFromUser }
        val perContactCounts = HashMap<String, Int>()
        for (m in messages) {
            val name = m.contact?.takeIf { it.isNotBlank() } ?: continue
            perContactCounts[name] = (perContactCounts[name] ?: 0) + 1
        }
        val byContact = perContactCounts.toList().sortedByDescending { it.second }
        if (byContact.isNotEmpty()) {
            val top = byContact.take(TOP_CONTACTS).joinToString(", ") { (name, count) -> "$name ($count)" }
            addPersonaFact(
                content = "Top $source contacts the user messages: $top.",
                traits = listOf("trait:top-contacts", "source:$source"),
                now = now,
            )?.let { written++ }
        }

        // 2) Peak hour band — which 4h window dominates outgoing
        //    message timestamps. Tells the agent about the user's
        //    rhythm ("you're a 9-11pm texter").
        val hourCounts = IntArray(24)
        val cal = Calendar.getInstance()
        for (m in userMessages) {
            cal.timeInMillis = m.tsMillis
            hourCounts[cal.get(Calendar.HOUR_OF_DAY)]++
        }
        val (bandLabel, bandCount) = peakBand(hourCounts)
        if (bandCount > 0) {
            addPersonaFact(
                content = "Peak $source texting time for the user: $bandLabel.",
                traits = listOf("trait:texting-rhythm", "source:$source"),
                now = now,
            )?.let { written++ }
        }

        // 3) Communication style — quick heuristic scan over the
        //    user's outbound text. Counts emoji density, abbrev
        //    presence ("lol", "btw", "rn", "u", "ur"), avg length.
        val style = classifyStyle(userMessages)
        if (style != null) {
            addPersonaFact(
                content = "User's $source communication style: $style.",
                traits = listOf("trait:style", "source:$source"),
                now = now,
            )?.let { written++ }
        }

        // 4) Totals for transparency.
        val inboundCount = messages.count { !it.isFromUser }
        addPersonaFact(
            content = "Imported $source history: ${messages.size} messages (${userMessages.size} sent, $inboundCount received).",
            traits = listOf("trait:import-summary", "source:$source"),
            now = now,
        )?.let { written++ }

        // 5) Gemma deep-extraction pass over sampled outgoing messages.
        //    Only the USER's own messages — we never want to extract
        //    facts about the user's contacts ("Mom likes coffee") or
        //    misattribute someone else's preferences to the user.
        //
        //    Chunks are bounded by char count rather than message
        //    count — short SMS chunks better, long WhatsApp paragraphs
        //    chunk smaller. Each chunk gets one Gemma extract pass; we
        //    de-dup at write-time so recurring statements across chunks
        //    collapse into one vault record.
        //
        //    Skipped silently if Gemma isn't loaded (user hasn't
        //    downloaded the model yet). The heuristic pass above is
        //    always present, so the import is still useful in that
        //    state — Gemma upgrades the result, doesn't gate it.
        if (gemma.isReady() && userMessages.isNotEmpty()) {
            val gemmaWritten = runGemmaPass(source, userMessages, now)
            written += gemmaWritten
            Log.d(TAG, "gemma pass wrote $gemmaWritten persona records over ${userMessages.size} outgoing $source messages")
        } else if (!gemma.isReady()) {
            Log.d(TAG, "skipping gemma pass — model not loaded")
        }

        // 6) PER-CONTACT pass. The user persona pass above is about
        //    the USER — it sees only outgoing messages, never tags
        //    learnings with a contact facet. That means imported
        //    WhatsApp history with Mom never lands in Mom's profile
        //    on the People screen — exactly the gap the user reported.
        //
        //    For each top contact in the import (above the
        //    MIN_PER_CONTACT_MESSAGES threshold), we now:
        //      - persist the back-and-forth itself as
        //        kind:message-history rows facetted with
        //        contact:<name>, direction:incoming|outgoing — same
        //        shape the live notification path uses. This lets
        //        ContactAnalyticsBuilder count interactions, find
        //        topics, and run its own Gemma summary / Big Five
        //        / key-points passes over real conversation text.
        //      - run a quick Gemma pass over the CONTACT's own
        //        messages to lift contact-specific facts (their
        //        topics, their voice) and store them too.
        if (byContact.isNotEmpty()) {
            val topContacts = byContact.take(MAX_CONTACTS_PER_IMPORT)
            for ((contactName, count) in topContacts) {
                if (count < MIN_PER_CONTACT_MESSAGES) continue
                val perContactWritten = ingestContact(source, contactName, messages, now)
                written += perContactWritten
                Log.d(TAG, "per-contact ingest for $contactName: $perContactWritten records")
            }
        }

        return Report(
            ok = true,
            recordsWritten = written,
            messagesAnalyzed = messages.size,
        )
    }

    /**
     * Persist a contact's import history into the vault facetted with
     * `contact:<name>` so [com.mythara.analytics.ContactAnalyticsBuilder]
     * folds it into the per-contact profile on the People screen.
     *
     * Two flavours of record:
     *  - `kind:message-history` — one row per chunk of conversation
     *    (40-60 messages compacted into a single text excerpt), so the
     *    builder's Gemma summary / Big Five / key-points passes have
     *    real back-and-forth language to work from. Stored at the
     *    working tier with `source:import-whatsapp` etc. — the
     *    self-organizer can later promote these to episodic.
     *  - `kind:contact-trait` — durable facts Gemma extracts from
     *    THIS contact's messages (topics they care about, their
     *    style). Semantic tier; same shape as the user-persona
     *    records but contact-scoped.
     *
     * Both gated by Gemma's availability — heuristic-only fallback
     * still writes the message-history rows (no LLM cost) so the
     * People screen at least shows interaction counts and topics
     * from facets the builder pulls.
     */
    private suspend fun ingestContact(
        source: String,
        contactName: String,
        allMessages: List<MessageRecord>,
        now: Long,
    ): Int {
        // Identify which sender label represents the user. WhatsApp's
        // export uses "You" in some locales/versions, but on modern
        // Android it usually uses the user's saved display name —
        // in which case isFromUser is false and contact = userName.
        // For a 1-on-1 export (exactly two distinct contact labels),
        // the user is whichever name ISN'T the target contact.
        val distinctSenders = allMessages
            .mapNotNull { it.contact }
            .filter { it.isNotBlank() }
            .distinct()
        val userSenderName: String? = when (distinctSenders.size) {
            2 -> distinctSenders.firstOrNull { !it.equals(contactName, ignoreCase = true) }
            else -> null // group chat or already-attributed; fall back to isFromUser flag
        }

        val convo = allMessages.filter { m ->
            // contact's incoming
            (m.contact?.equals(contactName, ignoreCase = true) == true && m.contact != userSenderName) ||
                // user's outgoing under "You" labelling
                (m.isFromUser && hasNearbyContactMessages(m, allMessages, contactName)) ||
                // user's outgoing under display-name labelling
                (userSenderName != null && m.contact?.equals(userSenderName, ignoreCase = true) == true)
        }
        if (convo.isEmpty()) return 0

        Log.d(
            TAG,
            "ingestContact($contactName): userSenderName=${userSenderName ?: "<\"You\"-flag>"} " +
                "convoSize=${convo.size} " +
                "in=${convo.count { it.contact?.equals(contactName, true) == true && !it.isFromUser }} " +
                "out=${convo.count { it.isFromUser || (userSenderName != null && it.contact?.equals(userSenderName, true) == true) }}",
        )
        var written = 0

        // a) Persist conversation excerpts as message-history rows.
        //    Even if Gemma isn't loaded, these enable the analytics
        //    builder to count interactions and surface topics from
        //    facets.
        val chunks = chunkConversation(convo)
        for (chunk in chunks.take(MAX_HISTORY_CHUNKS_PER_CONTACT)) {
            val excerpt = buildBidirectionalTranscript(chunk, userSenderName).take(HISTORY_EXCERPT_MAX_CHARS)
            if (excerpt.isBlank()) continue
            // Count user-direction messages by either signal: isFromUser
            // (when "You" labelling worked) OR contact = the inferred
            // user sender name.
            val outgoingInChunk = chunk.count { m ->
                m.isFromUser ||
                    (userSenderName != null && m.contact?.equals(userSenderName, ignoreCase = true) == true)
            }
            val incomingInChunk = chunk.size - outgoingInChunk
            val facets = buildList {
                add("kind:message-history")
                add("source:import-$source")
                add("contact:$contactName")
                add("app:com.whatsapp")
                add("direction:both")
                add("imported:true")
            }
            // Embed the excerpt when the local embedder is loaded so
            // SemanticRecall surfaces this history later when the user
            // chats about the same person / topic / event. Without an
            // embedding the recall layer falls back to facet+content
            // string matching, which mostly works on name mentions but
            // misses semantic similarity ("did Mom mention her surgery"
            // matching an excerpt where she said "the doctor said
            // recovery is going well").
            val embedding = runCatching {
                if (embedder.isReady()) embedder.embed(excerpt) else null
            }.getOrNull()
            val ok = runCatching {
                vault.add(
                    content = excerpt,
                    tier = Tier.Working,
                    src = "msg:import-$source",
                    facets = facets,
                    embedding = embedding,
                    embModel = if (embedding != null) com.mythara.secret.observe.embed.EmbeddingsModelStore.MODEL_ID else null,
                    conf = 0.7,
                    now = now,
                )
            }.getOrDefault(false)
            if (ok) written++
            Log.d(TAG, "  history chunk for $contactName: ${chunk.size} msgs (${incomingInChunk} in / ${outgoingInChunk} out) → ok=$ok embed=${embedding != null}")
        }

        // b) Gemma pass on the contact's OWN messages to extract
        //    contact-specific traits. Skip when Gemma not loaded —
        //    the builder will produce summary / Big Five / key
        //    points later from the message-history rows above.
        if (gemma.isReady()) {
            // Contact's own messages = NOT user-sent (either by
            // isFromUser flag OR by display-name labelling).
            val theirMessages = convo.filter { m ->
                !m.isFromUser &&
                    !(userSenderName != null && m.contact?.equals(userSenderName, ignoreCase = true) == true)
            }
            if (theirMessages.size >= MIN_PER_CONTACT_GEMMA_SAMPLE) {
                val theirChunks = chunkMessages(theirMessages).take(GEMMA_MAX_CHUNKS_PER_CONTACT)
                val collected = mutableListOf<String>()
                for (chunk in theirChunks) {
                    val transcript = buildTranscript(chunk)
                    val result = runCatching { gemma.extractWithMood(transcript) }.getOrNull() ?: continue
                    for (fact in result.facts) {
                        if (fact.content.isNotBlank()) collected.add(fact.content.trim())
                    }
                }
                val unique = mutableListOf<String>()
                for (line in collected) {
                    val lower = line.lowercase()
                    if (unique.none { it.lowercase().contains(lower) || lower.contains(it.lowercase()) }) {
                        unique.add(line)
                    }
                }
                for (line in unique.take(MAX_CONTACT_TRAITS)) {
                    val facets = buildList {
                        add("kind:contact-trait")
                        add("source:import-$source")
                        add("contact:$contactName")
                        add("trait:gemma-extracted-import")
                    }
                    val traitEmbedding = runCatching {
                        if (embedder.isReady()) embedder.embed(line) else null
                    }.getOrNull()
                    val ok = runCatching {
                        vault.add(
                            content = line,
                            tier = Tier.Semantic,
                            src = "persona:import-$source",
                            facets = facets,
                            embedding = traitEmbedding,
                            embModel = if (traitEmbedding != null) com.mythara.secret.observe.embed.EmbeddingsModelStore.MODEL_ID else null,
                            conf = 0.8,
                            now = now,
                        )
                    }.getOrDefault(false)
                    if (ok) written++
                }
            }
        }

        return written
    }

    /**
     * Some imports lose the contact attribution on user-sent rows
     * (it.contact is null when isFromUser is true). To reconstruct
     * the "user → contactName" thread we use a sliding-window
     * heuristic: a user-sent message belongs to the conversation
     * with contactName if a message from contactName appears within
     * NEARBY_WINDOW positions in the import order.
     */
    private fun hasNearbyContactMessages(
        userMsg: MessageRecord,
        all: List<MessageRecord>,
        contactName: String,
    ): Boolean {
        val idx = all.indexOf(userMsg)
        if (idx < 0) return false
        val lo = (idx - NEARBY_WINDOW).coerceAtLeast(0)
        val hi = (idx + NEARBY_WINDOW).coerceAtMost(all.size - 1)
        for (i in lo..hi) {
            val m = all[i]
            if (!m.isFromUser && m.contact?.equals(contactName, ignoreCase = true) == true) return true
        }
        return false
    }

    private fun chunkConversation(msgs: List<MessageRecord>): List<List<MessageRecord>> {
        if (msgs.isEmpty()) return emptyList()
        val out = mutableListOf<MutableList<MessageRecord>>()
        var current = mutableListOf<MessageRecord>()
        var currentChars = 0
        for (m in msgs.sortedBy { it.tsMillis }) {
            val len = m.text.length + 20
            if (currentChars + len > HISTORY_EXCERPT_MAX_CHARS && current.isNotEmpty()) {
                out.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            current.add(m)
            currentChars += len
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun buildBidirectionalTranscript(
        chunk: List<MessageRecord>,
        userSenderName: String? = null,
    ): String =
        chunk.joinToString("\n") { m ->
            val isUserSent = m.isFromUser ||
                (userSenderName != null && m.contact?.equals(userSenderName, ignoreCase = true) == true)
            val who = if (isUserSent) "User" else (m.contact ?: "Other")
            "$who: ${m.text}"
        }

    /**
     * Build chunks of the user's outgoing messages and feed each to
     * Gemma. Returns the number of vault records written.
     *
     * Why not feed everything at once: Gemma's context window is small
     * (~2K char prompt cap in [GemmaExtractor.MAX_TRANSCRIPT_CHARS]),
     * and the extraction quality degrades on giant chunks. Multiple
     * smaller passes also give the model a chance to surface different
     * traits per chunk; if we batched everything the latest content
     * would dominate.
     *
     * Cap at [GEMMA_MAX_CHUNKS] chunks total so a 3000-message import
     * doesn't camp on Gemma for 20 minutes. We sample evenly across
     * the timeline so early + recent voice both get represented.
     */
    private suspend fun runGemmaPass(
        source: String,
        userMessages: List<MessageRecord>,
        now: Long,
    ): Int {
        val chunks = chunkMessages(userMessages)
        if (chunks.isEmpty()) return 0
        val sampled = if (chunks.size <= GEMMA_MAX_CHUNKS) chunks else sampleEvenly(chunks, GEMMA_MAX_CHUNKS)
        val collected = mutableListOf<String>()
        for ((idx, chunk) in sampled.withIndex()) {
            val transcript = buildTranscript(chunk)
            val result = runCatching { gemma.extractWithMood(transcript) }.getOrNull()
            if (result == null) {
                Log.w(TAG, "gemma chunk $idx returned null result (model error?)")
                continue
            }
            for (fact in result.facts) {
                if (fact.content.isNotBlank()) collected.add(fact.content.trim())
            }
        }
        if (collected.isEmpty()) return 0

        // De-dup case-insensitively. Gemma frequently surfaces the same
        // trait across chunks ("user works in tech", "the user is a
        // software engineer") — embedding-based dedup would be ideal
        // but case-insensitive contains() catches the bulk for free.
        val unique = mutableListOf<String>()
        for (line in collected) {
            val lower = line.lowercase()
            if (unique.none { it.lowercase().contains(lower) || lower.contains(it.lowercase()) }) {
                unique.add(line)
            }
        }

        var written = 0
        for (line in unique.take(GEMMA_MAX_RECORDS)) {
            addPersonaFact(
                content = line,
                traits = listOf("trait:gemma-extracted", "source:$source"),
                now = now,
            )?.let { written++ }
        }
        return written
    }

    /** Group adjacent messages into chunks bounded by [CHUNK_MAX_CHARS]. */
    private fun chunkMessages(msgs: List<MessageRecord>): List<List<MessageRecord>> {
        if (msgs.isEmpty()) return emptyList()
        val out = mutableListOf<MutableList<MessageRecord>>()
        var current = mutableListOf<MessageRecord>()
        var currentLen = 0
        for (m in msgs) {
            val len = m.text.length + 2
            if (currentLen + len > CHUNK_MAX_CHARS && current.isNotEmpty()) {
                out.add(current)
                current = mutableListOf()
                currentLen = 0
            }
            current.add(m)
            currentLen += len
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /** Pick [count] evenly-spaced chunks across the timeline. */
    private fun <T> sampleEvenly(items: List<T>, count: Int): List<T> {
        if (count >= items.size) return items
        val step = items.size.toDouble() / count
        val out = mutableListOf<T>()
        var pos = 0.0
        repeat(count) {
            out.add(items[pos.toInt().coerceIn(0, items.size - 1)])
            pos += step
        }
        return out
    }

    /** Render a chunk as the transcript text passed to Gemma. */
    private fun buildTranscript(chunk: List<MessageRecord>): String =
        chunk.joinToString("\n") { it.text }.take(CHUNK_MAX_CHARS)

    private suspend fun addPersonaFact(
        content: String,
        traits: List<String>,
        now: Long,
    ): Boolean {
        val embedding = runCatching {
            if (embedder.isReady()) embedder.embed(content) else null
        }.getOrNull()
        val facets = buildList {
            add("kind:persona")
            add("source:message-import")
            addAll(traits)
        }
        return runCatching {
            vault.add(
                content = content,
                tier = Tier.Semantic,
                src = "persona:message-import",
                facets = facets,
                embedding = embedding,
                embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                conf = 0.85,
                now = now,
            )
        }.getOrElse {
            Log.w(TAG, "vault.add (persona/message-import) failed: ${it.message}")
            false
        }
    }

    private fun peakBand(hourCounts: IntArray): Pair<String, Int> {
        // Slide a 4-hour window across the 24-hour cycle (wrap-around)
        // and pick the one with the most messages.
        var bestStart = 0
        var bestSum = 0
        for (start in 0..23) {
            var sum = 0
            for (i in 0 until BAND_WIDTH) sum += hourCounts[(start + i) % 24]
            if (sum > bestSum) {
                bestSum = sum
                bestStart = start
            }
        }
        val endHour = (bestStart + BAND_WIDTH) % 24
        return "${formatHour(bestStart)}–${formatHour(endHour)}" to bestSum
    }

    private fun formatHour(h: Int): String = when (h) {
        0 -> "12am"
        12 -> "12pm"
        in 1..11 -> "${h}am"
        else -> "${h - 12}pm"
    }

    /** Quick style classification. Returns null if too few messages to classify. */
    private fun classifyStyle(userMessages: List<MessageRecord>): String? {
        if (userMessages.size < STYLE_MIN_MESSAGES) return null
        var emojiCount = 0
        var abbrevCount = 0
        var totalLen = 0
        for (m in userMessages) {
            totalLen += m.text.length
            // Emoji: any codepoint in the common emoji ranges
            var i = 0
            while (i < m.text.length) {
                val cp = m.text.codePointAt(i)
                if (cp in 0x1F000..0x1FFFF || cp in 0x2600..0x27BF) emojiCount++
                i += Character.charCount(cp)
            }
            // Abbreviations: token-bound match against a small set
            for (abbrev in ABBREVIATIONS) {
                if (TOKEN.matcher(m.text).results()
                        .anyMatch { it.group().equals(abbrev, ignoreCase = true) }
                ) abbrevCount++
            }
        }
        val avgLen = totalLen.toFloat() / userMessages.size
        val emojiRate = emojiCount.toFloat() / userMessages.size
        val abbrevRate = abbrevCount.toFloat() / userMessages.size
        return when {
            emojiRate >= 0.5f && abbrevRate >= 0.3f -> "casual, emoji + abbreviation heavy (avg ${avgLen.toInt()} chars/msg)"
            emojiRate >= 0.5f -> "casual, emoji-rich (avg ${avgLen.toInt()} chars/msg)"
            abbrevRate >= 0.3f -> "casual, abbreviation-heavy (avg ${avgLen.toInt()} chars/msg)"
            avgLen >= 100f -> "long-form, formal (avg ${avgLen.toInt()} chars/msg)"
            avgLen >= 50f -> "moderate length, conversational (avg ${avgLen.toInt()} chars/msg)"
            else -> "terse, brief (avg ${avgLen.toInt()} chars/msg)"
        }
    }

    companion object {
        private const val TAG = "Mythara/MsgImport"
        private const val TOP_CONTACTS = 5
        private const val BAND_WIDTH = 4
        private const val STYLE_MIN_MESSAGES = 50

        // Per-contact import tuning.
        private const val MAX_CONTACTS_PER_IMPORT = 5
        private const val MIN_PER_CONTACT_MESSAGES = 10
        private const val MIN_PER_CONTACT_GEMMA_SAMPLE = 6
        private const val MAX_HISTORY_CHUNKS_PER_CONTACT = 10
        private const val HISTORY_EXCERPT_MAX_CHARS = 1_400
        private const val GEMMA_MAX_CHUNKS_PER_CONTACT = 3
        private const val MAX_CONTACT_TRAITS = 15
        private const val NEARBY_WINDOW = 5

        // Gemma pass tuning.
        // CHUNK_MAX_CHARS sits below GemmaExtractor.MAX_TRANSCRIPT_CHARS
        // (2_000) so the prompt template + transcript stay under the
        // model's context window. GEMMA_MAX_CHUNKS bounds total inference
        // cost; ~10s per chunk on CPU means a 12-chunk import is ~2 min.
        // GEMMA_MAX_RECORDS caps the persona records we'll insert from a
        // single import so a chatty model can't flood the vault.
        private const val CHUNK_MAX_CHARS = 1_600
        private const val GEMMA_MAX_CHUNKS = 12
        private const val GEMMA_MAX_RECORDS = 30
        private val ABBREVIATIONS = setOf(
            "lol", "btw", "rn", "u", "ur", "tbh", "imo", "imho",
            "omw", "thx", "thnx", "k", "kk", "np", "ttyl", "brb",
        )
        // Lazy Java regex — Kotlin Regex doesn't have a streaming
        // results() API. Pattern is "word-character runs".
        private val TOKEN = java.util.regex.Pattern.compile("""\b[\w']+\b""")
    }
}
