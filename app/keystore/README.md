# The sideload signing key

`ping.p12` signs both the debug and the release build, and it is committed on purpose.

Android refuses to install an update whose signing key differs from the installed app — the
installer just says **"App not installed"**, and the only way forward is to uninstall first, which
deletes everything the app had stored locally. CI runners generate a fresh `~/.android/debug.keystore`
on every run, so without a committed key *every build was a different app*.

* alias: `ping`
* store and key password: `ping-sideload`
* self-signed RSA 2048, valid until 2056

It is **not a secret and not a security boundary**: anyone can read it, and anyone can rebuild this
app from source. It only proves "this APK came from Ping's own pipeline", which is all a sideloaded
personal app needs. The cryptography that protects messages does not involve this key at all.

To sign with your own key instead, create `keystore.properties` in the repository root (see
`docs/BUILD.md`) or set the `KEYSTORE_*` environment variables; those take precedence for the
release build.
