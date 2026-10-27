package dev.wahh.quotashell.core

import kotlin.math.sqrt

const val SIGNAL_GLYPHS = "░▒▓█▚▞0137ACEF"
const val DECODE_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-=:/<>"
val SIGNAL_NOISE_ROLES = listOf("accent", "notifications", "subtext2", "outline")

/** nu11signal's SplitMix64 finalization, with separate salts for each stream. */
fun signalMix(vararg values: Long): Long {
    var h = 0x9E3779B97F4A7C15uL.toLong()
    for (value in values) {
        h = h xor (value + 0x9E3779B97F4A7C15uL.toLong() + (h shl 6) + (h ushr 2))
        h = (h xor (h ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        h = (h xor (h ushr 27)) * 0x94D049BB133111EBuL.toLong()
        h = h xor (h ushr 31)
    }
    return h
}

private fun Long.pick(bound: Int): Int = (toULong() % bound.toUInt()).toInt()

private fun Long.unit(): Double = (ushr(11)).toDouble() / (1L shl 53).toDouble()

data class SignalShift(
    val row: Int,
    val cells: Int,
)

data class SignalCell(
    val row: Int,
    val column: Int,
    val glyph: Char,
    val colorRole: String,
)

data class SignalLossPlan(
    val shifts: List<SignalShift>,
    val staticRows: List<Int>,
    val corruptions: List<SignalCell>,
)

/** Protected rows are never touched. No sign/overlay or doubled-corruption variant. */
fun signalLossPlan(
    seed: Long,
    frame: Int,
    rows: Int,
    columns: Int,
    protectedRows: Set<Int> = emptySet(),
): SignalLossPlan {
    require(rows >= 0 && columns >= 0)
    val available = (0 until rows).filter { it !in protectedRows }
    if (available.isEmpty() || columns == 0) return SignalLossPlan(emptyList(), emptyList(), emptyList())
    val h = signalMix(seed, 106, frame.toLong())
    val remaining = available.toMutableList()
    val shifts =
        List(1 + h.pick(minOf(3, available.size))) { i ->
            val r = signalMix(h, 1, i.toLong())
            SignalShift(remaining.removeAt(r.pick(remaining.size)), (1 + (r ushr 8).pick(2)) * if ((r ushr 16).pick(2) == 0) 1 else -1)
        }
    val static = if ((h ushr 8).pick(3) == 0) listOf(available[signalMix(h, 2).pick(available.size)]) else emptyList()
    val capacity = available.size.toLong() * columns
    val occupied = mutableSetOf<Long>()
    val cells =
        List(minOf(4 + (h ushr 16).pick(7), capacity.coerceAtMost(10).toInt())) { i ->
            val r = signalMix(h, 4, i.toLong())
            var cell = (r.toULong() % capacity.toULong()).toLong()
            while (!occupied.add(cell)) cell = (cell + 1) % capacity
            val role = SIGNAL_NOISE_ROLES[(r ushr 48).pick(4)]
            SignalCell(
                available[(cell / columns).toInt()],
                (cell % columns).toInt(),
                SIGNAL_GLYPHS[(r ushr 32).pick(SIGNAL_GLYPHS.length)],
                role,
            )
        }
    return SignalLossPlan(shifts, static, cells)
}

fun decodeResolveTime(
    columnFraction: Double,
    random: Double,
): Double = 0.2 + 0.7 * (1 - sqrt(1 - columnFraction.coerceIn(0.0, 1.0))) + 0.1 * random.coerceIn(0.0, 1.0)

/** Only changed, non-space cells enter the reveal; each keeps its original cell width/style. */
fun decodeReveal(
    previous: String,
    current: String,
    seed: Long,
    row: Int,
    progress: Double,
): String =
    current
        .mapIndexed { column, char ->
            val h = signalMix(seed, 401, row.toLong(), column.toLong())
            val fraction = column.toDouble() / (current.length - 1).coerceAtLeast(1)
            if (previous.getOrNull(column) == char || char.isWhitespace() || signalMix(h, 2).unit() >= 0.5 ||
                progress >= decodeResolveTime(fraction, signalMix(h, 1).unit())
            ) {
                char
            } else {
                DECODE_GLYPHS[signalMix(h, (progress * 900 / 140).toLong()).pick(DECODE_GLYPHS.length)]
            }
        }.joinToString("")
