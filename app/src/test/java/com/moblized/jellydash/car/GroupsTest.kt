package com.moblized.jellydash.car

import com.moblized.jellydash.ui.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupsTest {
    @Test
    fun splitsIntoLetterRanges() {
        val names = listOf("Alien", "Amelie", "Babe", "Brazil", "Casablanca", "Dune", "Elf", "Fargo", "Heat")
        val g = Groups.split(names, size = 3, maxGroups = 10)
        assertEquals(listOf(0, 3, 6), g.map { it.start })
        assertEquals(listOf(3, 3, 3), g.map { it.count })
        assertEquals("A – Ba", g[0].label)
        assertEquals("Br – D", g[1].label)
        assertEquals("E – H", g[2].label)
    }

    @Test
    fun biggerGroupsWhenThereWouldBeTooManyRows() {
        val names = (1..100).map { "Title $it" }
        val g = Groups.split(names, size = 10, maxGroups = 5)
        assertEquals(5, g.size)
        assertEquals(100, g.sumOf { it.count })
    }

    @Test
    fun numbersGoUnderHash() {
        val g = Groups.split(listOf("12 Monkeys", "2012", "Zodiac"), size = 2, maxGroups = 10)
        assertEquals("#", g[0].label)
        assertEquals("Z", g[1].label)
    }

    @Test
    fun emptyLibrary() = assertEquals(0, Groups.split(emptyList(), 10, 10).size)

    @Test
    fun serverAddresses() {
        assertEquals("https://jellyfin.moblized.org", ServerAddress.normalize("jellyfin.moblized.org"))
        assertEquals("https://jellyfin.moblized.org", ServerAddress.normalize("https://jellyfin.moblized.org/web/#/home.html"))
        assertEquals("http://192.168.1.20:8096", ServerAddress.normalize("http://192.168.1.20:8096/"))
        assertEquals("https://example.com/jellyfin", ServerAddress.normalize("https://example.com/jellyfin/web/index.html"))
        assertEquals("https://example.com/webstuff", ServerAddress.normalize("https://example.com/webstuff"))
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("not a server"))
    }
}
