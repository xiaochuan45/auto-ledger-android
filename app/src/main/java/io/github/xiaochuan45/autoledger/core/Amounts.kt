package io.github.xiaochuan45.autoledger.core

/** Money is always stored as integer fen. No rounding or floating point is involved. */
object Amounts {
    private val decimal = Regex("[0-9]+(?:\\.[0-9]{1,2})?")

    /** UI input deliberately accepts no signs, grouping, currency suffixes or exponents. */
    fun parseMinor(text: String): Long? {
        val value = text.trim()
        if (value.length > 32 || !decimal.matches(value)) return null
        val pieces = value.split('.', limit = 2)
        val whole = pieces[0].toLongOrNull() ?: return null
        val fraction = pieces.getOrNull(1)?.padEnd(2, '0')?.toIntOrNull() ?: 0
        if (whole > (Long.MAX_VALUE - fraction) / 100) return null
        return whole * 100 + fraction
    }

    fun formatMinor(value: Long): String {
        // Using the decimal representation also handles Long.MIN_VALUE without abs overflow.
        val digits = value.toString().removePrefix("-").padStart(3, '0')
        return (if (value < 0) "-" else "") + digits.dropLast(2) + "." + digits.takeLast(2)
    }
}
