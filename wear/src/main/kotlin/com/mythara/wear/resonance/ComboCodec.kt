package com.mythara.wear.resonance

/**
 * The 2-tap-over-4-colors vocabulary used by `ResonancePad` and decoded
 * on the phone by `ResonanceComboMap`. The 4 buttons are 0=R, 1=A, 2=G,
 * 3=B; a combo code is `c1 * 4 + c2` (range 0..15) so the wire payload
 * stays a single integer.
 *
 * Same-colour combos are reserved for self-regulation protocols (easy
 * to learn eyes-free); cross-colour combos are discreet commands.
 * Codes not in [VALID] are reserved for v2 — taps that resolve to one
 * of those should buzz an error and be discarded by the pad.
 */
object ComboCodec {

    enum class Color(val id: Int) {
        Red(0), Amber(1), Green(2), Blue(3),
    }

    /** Encode a 2-colour sequence to its wire integer code. */
    fun encode(c1: Color, c2: Color): Int = c1.id * 4 + c2.id

    /** Decode an integer code back to the colour pair, or null if out of range. */
    fun decode(code: Int): Pair<Color, Color>? {
        if (code !in 0..15) return null
        val c1 = Color.entries.firstOrNull { it.id == code / 4 } ?: return null
        val c2 = Color.entries.firstOrNull { it.id == code % 4 } ?: return null
        return c1 to c2
    }

    /** True when [code] maps to a known v1 action. Pad uses this to
     *  reject taps that resolve to a reserved-for-v2 combo. */
    fun isValid(code: Int): Boolean = code in VALID

    /** Codes the v1 vocabulary actually uses. Phone-side
     *  `ResonanceComboMap` is the source of truth for what each one
     *  *does*; we only need to know which are accepted on the wire. */
    val VALID: Set<Int> = setOf(
        0,  // R,R → Calm
        5,  // A,A → Focus
        10, // G,G → Wind-down
        3,  // R,B → Check-in
        9,  // G,A → Mark moment
        15, // B,B → Start PTT
        2,  // R,G → End session
    )
}
