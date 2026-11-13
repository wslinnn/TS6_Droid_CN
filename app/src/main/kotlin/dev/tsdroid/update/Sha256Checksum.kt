package dev.tsdroid.update

/**
 * Parser for the `<apk>.sha256` release asset (the output of `sha256sum`).
 *
 * Format is one or more lines of "<64 hex digits>  <file name>" (a binary
 * marker `*` may precede the name, and the hash may be upper case). Kept
 * dependency-free so the format handling is unit-testable — the integrity
 * check is worthless if the parser silently accepts garbage.
 */
object Sha256Checksum {

    private val LINE = Regex("""^\s*([0-9a-fA-F]{64})(?:\s+[*]?\S.*)?\s*$""")

    /** Returns the lower-case hex digest of the first valid line, or null. */
    fun parse(text: String): String? {
        for (line in text.lineSequence()) {
            val match = LINE.matchEntire(line) ?: continue
            return match.groupValues[1].lowercase()
        }
        return null
    }
}
