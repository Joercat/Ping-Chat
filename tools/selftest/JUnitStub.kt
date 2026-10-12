package org.junit

/**
 * The two JUnit types the self-test suites touch, stubbed so the exact same sources can also be
 * compiled and run by a bare JVM (see `tools/selftest/run.sh`).
 *
 * The real classes come from `junit:junit:4.13.2` in the Gradle build; this file is only ever on
 * the classpath of the standalone runner, so nothing in the app depends on it.
 */
annotation class Test

object Assert {
    fun assertEquals(message: String?, expected: Any?, actual: Any?) {}
    fun assertTrue(message: String?, condition: Boolean) {}
}

object Assume {
    fun assumeTrue(message: String?, condition: Boolean) {}
}
