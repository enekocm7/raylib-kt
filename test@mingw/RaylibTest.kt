@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import raylib.GetRandomValue
import raylib.RAYLIB_VERSION
import raylib.RaylibVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaylibTest {
    @Test
    fun versionConstantIsExposed() {
        assertEquals("6.0", RAYLIB_VERSION)
    }

    @Test
    fun versionWrapperMatchesConstant() {
        assertEquals(RAYLIB_VERSION, RaylibVersion)
    }

    @Test
    fun getRandomValueReturnsWithinRange() {
        val value = GetRandomValue(10, 20)
        assertTrue(value in 10..20, "GetRandomValue returned $value")
    }
}
