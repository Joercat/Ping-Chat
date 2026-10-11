import com.ping.app.core.checks.RelayE2E

/**
 * Standalone entry point for the live relay suite.
 *
 *   node server/node/server.js --port 8080 &
 *   PING_RELAY=http://127.0.0.1:8080 tools/selftest/run.sh --relay
 *
 * The relay URL defaults to a locally running relay on port 8787 (`npx wrangler dev` in
 * `server/cloudflare/`), which is how the Cloudflare implementation is checked here.
 */
fun main() {
    val base = System.getenv("PING_RELAY") ?: "http://127.0.0.1:8787"
    println("relay: $base")
    val failures = RelayE2E.runAll(base)
    println("passed: ${RelayE2E.passed}")
    failures.forEach { println("  ✗ $it") }
    if (failures.isEmpty()) {
        println("E2E ALL GOOD ✅")
    } else {
        println("E2E FAILURES: ${failures.size}")
        kotlin.system.exitProcess(1)
    }
}
