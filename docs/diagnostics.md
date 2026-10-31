# Client diagnostics (unreleased)

Open Settings and choose DUM-SDK diagnostics in the main button grid, in the title menu or while playing. The button is appended through the original two-column row helper, preserving the order of other mods that share that helper. `/sdk` opens the same screen from the game. Escape/Back returns to the parent screen.

- **Overview:** connection/handshake state, server and client SDK versions, protocol and module API versions, Minecraft/Fabric/Java/OS, JVM heap use, module timings, server Fabric mods and pending restarts. Check for updates or open the fixed GitHub releases page through Minecraft's link confirmation.
- **Errors:** newest-first local history of up to 40 errors, with timestamps, stable installation/update codes and explanatory messages. History survives reconnects and normal restarts in `dum-sdk/diagnostics.json`. Errors loading/saving this history are shown too.
- **Files:** the five most recent Minecraft crash reports from `crash-reports/` and JVM fatal reports (`hs_err_pid*.log`) in the game folder, ordered by modification time. A short bounded preview is read from the newest report in each folder. Buttons open the newest report, its folder, `logs/latest.log`, the logs folder, `dum-sdk/` and `dum-sdk/cold-apply.log`. Missing files have disabled actions; folders can be created when opened.

The summary copy action includes versions, state, error messages and crash filenames/dates. It excludes client-log/crash-report contents and absolute game paths. Nothing is uploaded automatically. Error messages can contain module/server-provided diagnostics, so review a copied summary before sharing it. A forced termination or native crash does not always produce a report. No report means no report was found, not proof that the client did not crash.

File discovery and persistence run off the client thread. The open screen refreshes cached status once a second; Refresh Data repeats file discovery. File previews and controls remain scrollable/usable in smaller windows. Opening files uses the operating system's configured application.

## Self-update states

Notifications cover checking, downloading, signature verification, waiting for a retry, an up-to-date client, readiness for restart and terminal failure. The same current state is visible in Overview. A downloaded update is installed by the helper after the game exits; no running jar is replaced.

Network/DNS errors, HTTP 408/429 and HTTP 5xx responses get at most three attempts, with 5 and 15 second pauses. Each failed attempt records its error and announces the upcoming retry. A terminal error recommends downloading the SDK manually from GitHub. A failed connection is described as failure to reach GitHub and a suggestion to check the internet, rather than a claim about the machine's overall connectivity.

Permanent HTTP errors, TLS validation, malformed release metadata, file-size violations, invalid signatures, mismatched versions and local storage/helper-launch failures stop immediately. Repeating these automatically would not repair the file or validation failure. The manual check action becomes available after failure; it cannot create parallel workers or discard an already prepared update.

Local updater codes: `UPDATE_NETWORK`, `UPDATE_DNS`, `UPDATE_TLS`, `UPDATE_HTTP_<status>`, `UPDATE_RELEASE`, `UPDATE_SIZE`, `UPDATE_SIGNATURE`, `UPDATE_VERSION`, `UPDATE_STORAGE`, `UPDATE_INTERRUPTED`, `UPDATE_INTERNAL`. These are separate from protocol wire error IDs. Module runtime errors use `MODULE_RUNTIME`; file actions use `DIAGNOSTIC_FILES`/`DIAGNOSTIC_OPEN`.

Self-update stays disabled in normal development clients. Existing `-PupdateTest=<release-json-url>` enables test-mode updates; only explicit update test mode permits HTTP fixture URLs and development signatures. Production update URLs require HTTPS and production clients trust only the release key.
