package com.mythara.music

import android.util.Log
import com.mythara.data.MusicVocabularyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The shared vocabulary that the user and agent develop together in
 * Music Mode. Translates content tokens (lowercased + stripped) into
 * [Motif]s. New tokens are minted deterministically from a hash of
 * the token over the [PENTATONIC] scale, so the same word always
 * sounds the same on first encounter — which is the whole point: the
 * vocabulary has to be predictable for the user to learn it.
 *
 * The [confidenceFlow] / [reinforce] surface lets the chat UI feed
 * the user's decode-tap signal back: a "got it!" tap calls
 * [reinforce(token, hit=true)]; a "show me" tap calls it with
 * `hit=false`. After enough misses (or low confidence), [reinforce]
 * mutates the motif onto a fresh pattern (incrementing
 * [Motif.generation]), giving learnability another shot.
 *
 * State is persisted in [MusicVocabularyStore] (a DataStore JSON
 * blob) — high-frequency mutable state lives there rather than in
 * the [com.mythara.secret.observe.vault.LearningVault], which is
 * reserved for durable knowledge.
 */
@Singleton
class MusicVocabulary @Inject constructor(
    private val store: MusicVocabularyStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache: MutableMap<String, Motif> = mutableMapOf()
    private val cacheLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var loaded = false
    @Volatile private var flushJob: Job? = null

    private val _vocab = MutableStateFlow<Map<String, Motif>>(emptyMap())
    /** Hot view of the entire vocabulary — for the inspector UI to
     *  render a list / count. */
    val vocab: StateFlow<Map<String, Motif>> = _vocab.asStateFlow()

    init {
        // Hydrate from disk eagerly so the first agent reply doesn't
        // pay a DataStore round-trip per token.
        scope.launch { ensureLoaded() }
    }

    /** Get the motif for [token], minting a fresh one if the vocabulary
     *  hasn't seen it yet. Token is normalised internally (lowercased,
     *  punctuation stripped, leading/trailing whitespace dropped). */
    suspend fun motifFor(token: String): Motif {
        val key = normalise(token)
        if (key.isEmpty()) return SILENT_MOTIF
        ensureLoaded()
        cacheLock.withLock {
            cache[key]?.let { return it }
            val fresh = mintMotif(key, generation = 0)
            cache[key] = fresh
            scheduleFlush()
            _vocab.value = cache.toMap()
            return fresh
        }
    }

    /** Record a user's decode-tap signal. [hit] = the user identified
     *  the motif before revealing the text. Updates persisted on the
     *  next debounced flush. */
    suspend fun reinforce(token: String, hit: Boolean) {
        val key = normalise(token)
        if (key.isEmpty()) return
        ensureLoaded()
        cacheLock.withLock {
            val existing = cache[key] ?: return
            val updated = if (hit) {
                existing.copy(hits = existing.hits + 1)
            } else {
                // Miss path: bump misses; if confidence has decayed past
                // the floor and we have enough signal, mutate to a
                // fresh pitch pattern (next generation) so the user
                // gets a new shot at learning it.
                val nextMisses = existing.misses + 1
                val total = existing.hits + nextMisses
                if (total >= MIN_RESHAPE_SIGNALS &&
                    (existing.hits.toFloat() / total) < RESHAPE_CONFIDENCE_FLOOR
                ) {
                    Log.d(
                        TAG,
                        "reshaping motif for '$key' (gen ${existing.generation} → " +
                            "${existing.generation + 1}, conf=${existing.confidence})",
                    )
                    mintMotif(key, generation = existing.generation + 1)
                } else {
                    existing.copy(misses = nextMisses)
                }
            }
            cache[key] = updated
            _vocab.value = cache.toMap()
            scheduleFlush()
        }
    }

    /** Debounced persistence — many reinforce calls in quick
     *  succession (e.g. while a long reply plays out) collapse into a
     *  single DataStore write. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DEBOUNCE_MS)
            persist()
        }
    }

    private suspend fun persist() {
        val snapshot = cacheLock.withLock { cache.toMap() }
        val obj = buildJsonObject {
            for ((k, m) in snapshot) {
                put(k, buildJsonObject {
                    put("n", buildJsonArray { m.notes.forEach { add(it) } })
                    put("h", m.hits)
                    put("m", m.misses)
                    put("g", m.generation)
                })
            }
        }
        runCatching { store.setVocab(obj.toString()) }
            .onFailure { Log.w(TAG, "vocab persist failed: ${it.message}") }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        cacheLock.withLock {
            if (loaded) return
            val raw = runCatching { store.vocabFlow().first() }.getOrDefault("{}")
            val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }
                .getOrDefault(JsonObject(emptyMap()))
            for ((k, v) in parsed) {
                runCatching {
                    val obj = v.jsonObject
                    val notes = obj["n"]?.jsonArray?.map { it.jsonPrimitive.float } ?: emptyList()
                    val h = obj["h"]?.jsonPrimitive?.int ?: 0
                    val m = obj["m"]?.jsonPrimitive?.int ?: 0
                    val g = obj["g"]?.jsonPrimitive?.int ?: 0
                    if (notes.isNotEmpty()) cache[k] = Motif(notes, h, m, g)
                }.onFailure { Log.w(TAG, "skipping malformed vocab entry '$k': ${it.message}") }
            }
            // One-shot migrations on load:
            //
            //  1. Pentatonic legacy — if any motif uses pitches not in
            //     the OM-harmonic scale, the vocabulary was minted
            //     under the old algorithm. Wipe so we start fresh.
            //  2. Collision check — under the original (non-unique)
            //     minter, two distinct tokens could hash onto the
            //     same note pattern and become indistinguishable.
            //     If we find any such pair, wipe and re-mint
            //     uniquely going forward. Same outcome as the legacy
            //     case (clean caveman-tier slate), no-op when the
            //     vocabulary is already a unique set.
            val omSet = OM_HARMONICS.toSet()
            val hasLegacy = cache.values.any { m -> m.notes.any { it !in omSet } }
            val signatures = cache.values.map { motifSignature(it.notes) }
            val hasCollisions = signatures.size != signatures.toSet().size
            if (hasLegacy || hasCollisions) {
                val why = listOfNotNull(
                    if (hasLegacy) "pentatonic-legacy" else null,
                    if (hasCollisions) "non-unique-motifs" else null,
                ).joinToString(" + ")
                Log.d(TAG, "clearing vocabulary: $why (${cache.size} motif(s))")
                cache.clear()
                scheduleFlush()
            }
            _vocab.value = cache.toMap()
            loaded = true
            Log.d(TAG, "loaded vocabulary: ${cache.size} motif(s)")
        }
    }

    /** Deterministic motif minter with **uniqueness guarantee** AND
     *  a **phonetic first attempt**. Each token gets a fresh
     *  signature that doesn't already exist in the vocabulary; the
     *  FIRST signature we try is derived from the word's own
     *  English vowels (see [VOWEL_TO_OM]) so the tone has an
     *  audible relationship to the word that helps the user learn.
     *  Examples:
     *
     *    "om"     → /o/                 → [136.1]                 (pure OM)
     *    "yes"    → /e/                 → [680.5]                 (bright)
     *    "you"    → /o/, /u/            → [136.1, 544.4]          (deep+warm)
     *    "hello"  → /e/, /o/            → [680.5, 136.1]          (bright→deep)
     *
     *  If the vowel-based candidate collides with another token's
     *  signature (e.g. both "go" and "no" boil down to /o/), we fall
     *  back to the salted-hash retry path. So the dictionary stays
     *  unique while still leaning into phonetic similarity where
     *  possible. Reshapes of the same word (generation > 0) skip
     *  the vowel-first attempt directly so they explore fresh
     *  patterns.
     *
     *  Algorithm:
     *  1. Compute the user's tier from current cache size.
     *  2. (gen 0 only) Try vowel-derived notes truncated/padded to
     *     `tier` length. Accept if fresh.
     *  3. For salts 0..N-1 at this tier, generate a candidate via
     *     stable hash. Accept if fresh.
     *  4. If exhausted at this tier, escalate to the next tier and
     *     retry — first with vowel-derived notes at the bigger
     *     count (more of the word's phonemes get encoded), then
     *     salted hash.
     *  5. Ultimate fallback: highest-tier base pattern. Should
     *     never trigger in practice (tier-5 alone is 9^5 unique). */
    private fun mintMotif(key: String, generation: Int): Motif {
        val baseTier = notesPerMotifFor(cache.size).coerceIn(1, MAX_NOTES_PER_MOTIF)
        val existing: Set<String> = cache.entries
            .filter { it.key != key }
            .map { motifSignature(it.value.notes) }
            .toSet()

        for (tier in baseTier..MAX_NOTES_PER_MOTIF) {
            // Phonetic first attempt — only at gen 0; reshapes
            // explicitly want a fresh non-phonetic pattern.
            if (generation == 0) {
                val phonetic = vowelDerivedNotes(key, tier)
                if (phonetic != null && motifSignature(phonetic) !in existing) {
                    return Motif(notes = phonetic, hits = 0, misses = 0, generation = 0)
                }
            }
            val possible = pow9(tier)
            val budget = (possible.coerceAtMost(MAX_SALT_ATTEMPTS_PER_TIER.toLong())).toInt()
            for (salt in 0 until budget) {
                val seed = stableHash("$key|$generation|$salt")
                val notes = pickNotes(seed, tier)
                if (motifSignature(notes) !in existing) {
                    return Motif(notes = notes, hits = 0, misses = 0, generation = generation)
                }
            }
        }

        Log.w(TAG, "vocabulary exhausted minting '$key' — collision possible")
        val seed = stableHash("$key|$generation")
        return Motif(
            notes = pickNotes(seed, MAX_NOTES_PER_MOTIF),
            hits = 0, misses = 0, generation = generation,
        )
    }

    /** Extract the [count] most-representative vowels from [key] and
     *  map each to its OM-harmonic neighbour. Returns null if the
     *  token has no vowels at all (e.g. "x", "vs") — those fall
     *  through to the hash path so they still get a tone, just not
     *  a phonetic one.
     *
     *  When the token has more vowels than [count], we take the
     *  first [count] in order (the first phonemes are what the
     *  listener hears first and best correlates with the spelling).
     *  When it has fewer, we pad with the LAST vowel — a
     *  one-vowel word like "om" at tier 3 becomes [/o/, /o/, /o/],
     *  a sustained OM motif that feels right for words centred on
     *  a single phoneme. */
    private fun vowelDerivedNotes(key: String, count: Int): List<Float>? {
        val vowels = key.mapNotNull { ch -> VOWEL_TO_OM[ch] }
        if (vowels.isEmpty()) return null
        val out = ArrayList<Float>(count)
        for (i in 0 until count) {
            out.add(vowels[if (i < vowels.size) i else vowels.size - 1])
        }
        return out
    }

    private fun pickNotes(seed: Long, count: Int): List<Float> =
        (0 until count).map { i ->
            val mixed = seed.rotateLeft(i * 7) xor (i.toLong() * 0x9E3779B9L)
            OM_HARMONICS[(abs(mixed) % OM_HARMONICS.size).toInt()]
        }

    /** Two-decimal Hz signature — collisions on equal-rounded notes
     *  count as collisions (they sound identical). Order matters:
     *  [136.1, 272.2] and [272.2, 136.1] are distinct phrases. */
    private fun motifSignature(notes: List<Float>): String =
        notes.joinToString(",") { "%.1f".format(it) }

    private fun pow9(exp: Int): Long {
        var r = 1L
        repeat(exp) { r *= OM_HARMONICS.size.toLong() }
        return r
    }

    private fun stableHash(s: String): Long {
        // FNV-1a 64-bit. Avoids dependency on JVM hashCode flakiness
        // across process restarts (which would re-mint motifs on app
        // upgrade — exactly the wrong behaviour for a learnable
        // vocabulary).
        var h = -3750763034362895579L // 0xCBF29CE484222325
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return h
    }

    private fun normalise(token: String): String {
        return token.lowercase()
            .replace(NON_WORD, "")
            .trim()
    }

    /** Notes-per-motif at the user's current learning tier. Grows
     *  monotonically with vocabulary size:
     *
     *   0–7 words   1 note   "caveman" — one sound per word, max
     *                        eight unique words before collisions
     *                        force a step up
     *   8–31        2 notes  "toddler" — pairs
     *   32–127      3 notes  "adult"   — phrases (the original tier)
     *   128–511     4 notes  "advanced"
     *   512+        5 notes  "expert" cap
     *
     *  The tier is read at mint-time only — existing motifs keep
     *  whatever complexity they were born at, so a word you learned
     *  as a single caveman tone stays a single caveman tone even
     *  after the vocabulary grows past the threshold. Only fresh
     *  words climb the ladder. */
    fun currentTierNotesPerMotif(): Int = notesPerMotifFor(cache.size)

    private fun notesPerMotifFor(vocabSize: Int): Int = when {
        vocabSize < 8 -> 1
        vocabSize < 32 -> 2
        vocabSize < 128 -> 3
        vocabSize < 512 -> 4
        else -> 5
    }

    companion object {
        private const val TAG = "Mythara/MusicVocab"

        /** Hard cap on a single motif's note count. The render loop
         *  in [MusicToneEngine] plays each note for ~180 ms, so a
         *  cap of 5 means a single word is voiced in well under
         *  1.5 s even at the top tier. */
        const val MAX_NOTES_PER_MOTIF = 5

        /** Harmonic series of 136.1 Hz — the Sanskrit "OM" base
         *  frequency (Hans Cousto's calculation, derived from Earth's
         *  orbital period via successive octave reductions; long
         *  associated with the OM syllable in Indian classical and
         *  contemporary sound-healing practice).
         *
         *  Includes the fundamental itself so every motif can resolve
         *  back to the OM tone, plus the 2nd–9th harmonics for upper
         *  voices. Every pitch here is consonant with every other,
         *  since they all sit on the natural overtone series of one
         *  root.
         *
         *  1st  136.1 Hz   OM fundamental ॐ
         *  2nd  272.2 Hz   octave above OM
         *  3rd  408.3 Hz   perfect-fifth above the octave
         *  4th  544.4 Hz   two octaves above OM
         *  5th  680.5 Hz   two octaves + major-third
         *  6th  816.6 Hz   two octaves + perfect-fifth
         *  7th  952.7 Hz   ≈ minor-seventh above the 4th
         *  8th  1088.8 Hz  three octaves above OM
         *  9th  1224.9 Hz  three octaves + major-second */
        val OM_HARMONICS: List<Float> = listOf(
            136.1f, 272.2f, 408.3f, 544.4f, 680.5f,
            816.6f, 952.7f, 1088.8f, 1224.9f,
        )

        /** Backwards-compat alias — keeps any callers that still
         *  reference the old name compiling. */
        @Deprecated("Use OM_HARMONICS", ReplaceWith("OM_HARMONICS"))
        val PENTATONIC: List<Float> get() = OM_HARMONICS

        /** Sentinel for tokens that normalise to the empty string. */
        val SILENT_MOTIF = Motif(notes = emptyList())

        /** Reshape a motif when confidence < [RESHAPE_CONFIDENCE_FLOOR]
         *  AND we've had at least this much user feedback for it. */
        private const val MIN_RESHAPE_SIGNALS = 3
        private const val RESHAPE_CONFIDENCE_FLOOR = 0.34f

        /** DataStore writes are debounced — a long reply may reinforce
         *  many tokens; only flush once after the burst settles. */
        private const val FLUSH_DEBOUNCE_MS = 1_500L

        /** Cap on how many salted candidates we'll generate at one
         *  tier before escalating. With 9 OM harmonics, even tier 2
         *  has 81 unique combos so this is generous; anything beyond
         *  this is taking long enough that bumping the tier is
         *  cheaper than continuing to search. */
        private const val MAX_SALT_ATTEMPTS_PER_TIER = 256

        /** English vowel → OM-harmonic mapping. Each vowel gets the
         *  harmonic that best matches its acoustic character — high
         *  front vowels (i) sit at the bright end of the spectrum,
         *  back round vowels (o, u) sit at the deep end. Crucially
         *  'o' maps to the 136.1 Hz OM fundamental itself: a word
         *  like "om" or "go" or "no" rings on the actual Sanskrit
         *  OM tone, which is the kind of phonetic resonance that
         *  makes the language feel like it's spelling itself.
         *
         *  Consulted on a word's first encounter so the tone has an
         *  immediate audible relationship to the word; falls back
         *  to hash-salted retries if a phonetic candidate would
         *  collide with another already-minted word. The dictionary
         *  stays unique while leaning into phonetic similarity
         *  wherever possible.
         *
         *  This is the seed of the evolving language — the user's
         *  intuition learns "/o/ = deep tone" naturally because
         *  every "o"-word resonates on the OM, and so on. Future
         *  iterations can layer richer phonetics (consonants,
         *  diphthongs, stress) on top of this foundation. */
        val VOWEL_TO_OM: Map<Char, Float> = mapOf(
            'a' to 408.3f,      // open central — 3rd harmonic, resonant
            'e' to 680.5f,      // mid front — 5th, bright
            'i' to 1088.8f,     // high front — 8th, piercing top
            'o' to 136.1f,      // back round — OM fundamental, deep
            'u' to 544.4f,      // high back — 4th, warm
            'y' to 952.7f,      // i-like vowel use — 7th, edge-bright
        )

        private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
    }
}
