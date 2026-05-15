package com.mythara.resonance

/**
 * Phone-side decode table for the wire codes shipped by the watch's
 * `ComboCodec`. Single source of truth for what each combo *means* on
 * the phone — the watch only knows that a given code is "valid", not
 * what protocol or command it triggers.
 *
 * The table mirrors the v1 vocabulary documented in the Resonance Mode
 * plan; reserved codes (anything not in [decode]) return null so the
 * controller can log + ignore them.
 */
object ResonanceComboMap {

    /** Decode a wire code (0..15) to its [ResonanceCommand], or null
     *  if the code isn't in the v1 vocabulary. */
    fun decode(code: Int): ResonanceCommand? = when (code) {
        // Same-colour combos → regulation protocols.
        0 -> ResonanceCommand.StartProtocol(ResonanceCommand.Protocol.Calm)        // R,R
        5 -> ResonanceCommand.StartProtocol(ResonanceCommand.Protocol.Focus)       // A,A
        10 -> ResonanceCommand.StartProtocol(ResonanceCommand.Protocol.WindDown)   // G,G

        // Cross-colour combos → discreet commands.
        3 -> ResonanceCommand.FireCommand(ResonanceCommand.Command.CheckIn)        // R,B
        9 -> ResonanceCommand.FireCommand(ResonanceCommand.Command.MarkMoment)     // G,A
        15 -> ResonanceCommand.FireCommand(ResonanceCommand.Command.StartPtt)      // B,B
        2 -> ResonanceCommand.FireCommand(ResonanceCommand.Command.EndSession)     // R,G

        else -> null
    }
}
