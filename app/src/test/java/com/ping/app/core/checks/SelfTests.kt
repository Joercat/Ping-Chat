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
        // A suite that ran nothing would also report zero failures, and a green CI run has to mean
        // the checks really executed. The floor is well below the current count on purpose.
        assertTrue(
            "the crypto/protocol suite ran only ${CryptoCoreTest.checks} checks — it used to run " +
                "more than 300, so something is skipping them",
            CryptoCoreTest.checks >= 300
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
        // Same guard as above: the relay suite must have actually talked to the relay.
        assertTrue(
            "the relay suite passed only ${RelayE2E.passed} checks — it used to pass more than 40, " +
                "so the live end-to-end run is not really happening",
            RelayE2E.passed >= 40
        )
    }
}
