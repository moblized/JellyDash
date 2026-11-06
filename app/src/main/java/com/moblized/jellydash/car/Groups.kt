package com.moblized.jellydash.car

import kotlin.math.ceil

/** A slice of a library sorted A–Z: where it starts, how many titles, and a label like "A – C". */
data class Group(val start: Int, val count: Int, val label: String)

object Groups {
    /**
     * Splits [names] (already sorted) into groups of at most [size], fewer if that would need more
     * than [maxGroups] rows. Labels use one letter, or two where neighbours share their first letter.
     */
    fun split(names: List<String>, size: Int, maxGroups: Int): List<Group> {
        if (names.isEmpty()) return emptyList()
        val per = maxOf(size, ceil(names.size / maxGroups.coerceAtLeast(1).toDouble()).toInt())
        val groups = mutableListOf<Group>()
        var start = 0
        while (start < names.size) {
            val end = minOf(start + per, names.size)
            val first = names[start]
            val last = names[end - 1]
            val prevLast = if (start > 0) names[start - 1] else null
            val nextFirst = if (end < names.size) names[end] else null
            val a = key(first, sharesLetter(first, prevLast))
            val b = key(last, sharesLetter(last, nextFirst))
            groups += Group(start, end - start, if (a == b) a else "$a – $b")
            start = end
        }
        return groups
    }

    private fun letter(s: String): Char {
        val c = s.trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (c.isLetter()) c else '#'
    }

    private fun sharesLetter(name: String, neighbour: String?) = neighbour != null && letter(name) == letter(neighbour) && letter(name) != '#'

    private fun key(name: String, twoLetters: Boolean): String {
        val l = letter(name)
        if (!twoLetters || l == '#') return l.toString()
        val second = name.trim().getOrNull(1)?.lowercaseChar()?.takeIf { it.isLetterOrDigit() } ?: return l.toString()
        return "$l$second"
    }
}
