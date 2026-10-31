# Unreleased

- Separate the outdated-client restart instructions and stable error code into paragraphs, with the manual download hint on its own line. Existing `messages.yml` overrides take precedence; update `handshake.outdated-client` and `handshake.error-code` overrides to adopt this spacing.
- Add a Russian "Go to GitHub" button above the return button on disconnect screens linking to the SDK releases. The button uses Minecraft's link confirmation and opens only the fixed SDK release page; older installed clients need a mod update to gain this button. Existing `messages.yml` overrides remain unchanged.

- Add the DUM-SDK menu to the main Settings button group with Overview, Updates, Errors and Files tabs; `/sdk` opens the same screen. Include module timings, server/session state, SDK/protocol/runtime versions, memory use, updater state and pending restarts.
- Append the diagnostics button through the existing Settings grid row helper, so it follows the normal left-to-right order and shares the next free cell with mods using that helper.
- Keep a bounded local error history across restarts, including updater, installation, disconnect and module failures. Add crash-report/JVM fatal report discovery, previews, copyable summaries and file/folder actions for client logs, reports and the cold-install helper.
- Show Russian update progress, retries and terminal error notifications. Retry temporary network/HTTP errors up to three attempts with 5/15 second pauses; reject invalid metadata/signatures without retry. Offer manual GitHub download and an on-demand check after failure, without concurrent downloads or replacing a ready update plan.

- Browse published release descriptions inside Updates with pagination, fixed-repository tag links, cached offline reading and a compact layout. Release-history loading is separate from signed executable updates.
- Show searchable Modifications only for an accepted SDK server, plus a pause-menu shortcut. Include only the current server catalog's modules and Fabric mods with versions, authors, descriptions, dependencies, activity, validation status and timings. Failed optional entries remain visible; disconnect removes the session inventory.
- Accept optional module `name`, `description` and string-array `authors` metadata without changing the wire protocol or module API version. Older jars remain supported; unsupported presentation fields are ignored.
- Present each modification as its own card, with responsive one/two-column layout, mouse/keyboard navigation and full details on click/Enter. Returning preserves search, selection and scroll; details can be copied.
- Anchor card hover descriptions to the mouse pointer with native screen-edge positioning, instead of anchoring them to the entire scroll container.

- Remove the Server/Client/All modification filters and exclude local Fabric mods and other servers' cached mods. Use the accepted session state consistently for the Connected indicator, Modifications tab and pause shortcut.
