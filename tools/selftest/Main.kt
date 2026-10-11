import com.ping.app.core.checks.CryptoCoreTest

/**
 * Standalone entry point for the crypto/protocol self-tests.
 *
 *   tools/selftest/run.sh            # compiles the core + the suites and runs them
 *
 * The Gradle build runs the same suites through `SelfTests.kt`; this runner exists so the checks
 * can be executed with nothing but a JDK and the Kotlin compiler (no Android SDK, no network).
 */
fun main() {
    val failures = CryptoCoreTest.runAll()
    println("checks run: ${CryptoCoreTest.checks}")
    if (failures == 0) {
        println("ALL GOOD ✅")
    } else {
        println("FAILURES: $failures")
        CryptoCoreTest.failures.forEach { println("  ✗ $it") }
        kotlin.system.exitProcess(1)
    }
}
