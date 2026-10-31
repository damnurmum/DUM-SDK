# Unreleased

- Separate the outdated-client restart instructions and stable error code into paragraphs, with the manual download hint on its own line. Existing `messages.yml` overrides take precedence; update `handshake.outdated-client` and `handshake.error-code` overrides to adopt this spacing.
- Add a Russian "Go to GitHub" button above the return button on disconnect screens linking to the SDK releases. The button uses Minecraft's link confirmation and opens only the fixed SDK release page; older installed clients need a mod update to gain this button. Existing `messages.yml` overrides remain unchanged.

- Move client diagnostics into the main Settings button group with Overview, Errors and Files tabs; `/sdk` opens the same screen. Include module timings, server/session state, SDK/protocol/runtime versions, memory use, updater state and pending restarts.
- Append the diagnostics button through the existing Settings grid row helper, so it follows the normal left-to-right order and shares the next free cell with mods using that helper.
- Keep a bounded local error history across restarts, including updater, installation, disconnect and module failures. Add crash-report/JVM fatal report discovery, previews, copyable summaries and file/folder actions for client logs, reports and the cold-install helper.
- Show Russian update progress, retries and terminal error notifications. Retry temporary network/HTTP errors up to three attempts with 5/15 second pauses; reject invalid metadata/signatures without retry. Offer manual GitHub download and an on-demand check after failure, without concurrent downloads or replacing a ready update plan.
