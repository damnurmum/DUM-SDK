# Unreleased

- Separate the outdated-client restart instructions and stable error code into paragraphs, with the manual download hint on its own line. Existing `messages.yml` overrides take precedence; update `handshake.outdated-client` and `handshake.error-code` overrides to adopt this spacing.
- Add a Russian "Go to GitHub" button above the return button on disconnect screens linking to the SDK releases. The button uses Minecraft's link confirmation and opens only the fixed SDK release page; older installed clients need a mod update to gain this button. Existing `messages.yml` overrides remain unchanged.
