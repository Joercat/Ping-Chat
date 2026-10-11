package com.ping.app.core.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * JUnit entry points, so `./gradlew test` actually runs the self-tests in CI.
 *
 * The suites themselves are framework-free on purpose (they also run from a bare JVM and from the
 * debug screen inside the app); this file only turns their result into a pass/fail for JUnit.
 */
class CryptoCoreJUnitTest {

    @Test
    fun cryptoAndProtocolAreCorrect() {
        val failures = CryptoCoreTest.runAll()
        assertEquals(
            "crypto/protocol failures:\n" + CryptoCoreTest.failures.joinToString("\n"),
            0,
            failures
        )
    }
}

/**
 * Runs the live relay suite when a relay is configured:
 *
 * ```bash
 * node server/node/server.js --port 8080 &
 * PING_RELAY=http://127.0.0.1:8080 ./gradlew testDebugUnitTest
 * ```
 *
 * Without `PING_RELAY` the test is skipped instead of failing, so the normal build stays green.
 */
class RelayE2EJUnitTest {

    @Test
    fun relayBehavesEndToEnd() {
        val base = System.getenv("PING_RELAY")
        assumeTrue("PING_RELAY is not set — skipping the live relay suite", !base.isNullOrBlank())
        val failures = RelayE2E.runAll(base!!)
        assertTrue("relay failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
