# DUM-SDK agent instructions

These instructions capture the author's rules from the DUM-SDK Claude handoff and conversation. Use the current repository as the source of truth for implementation details; the handoff describes an earlier state.

## Communication and repository style

- Talk with the author in Russian. Keep reports concrete: state what changed, how it was checked, what was not checked, and whether anything was released.
- Keep repository content in English, including code, comments, documentation, and commit messages. Text shown to players in the game must be in Russian.
- Do not use Unicode en dashes, em dashes, or the Unicode ellipsis in repository files, commit messages, or chat. Use ASCII punctuation instead.
- Do not add an AI agent `Co-Authored-By` trailer to commits.
- Keep player-facing text short and consistent with existing DUM messages and colors. Check that symbols render in the Minecraft font.

## Changes and verification

- Do not increase SDK or example/module versions for ordinary development, fixes, documentation changes, commits or pushes. Keep the last published SDK version in `gradle.properties` until the author explicitly asks for a release/version change.
- Only when the author asks for a release, choose the next version for the accumulated changes, update it and prepare/publish the requested release. A commit or push alone is not permission to release.
- Keep release descriptions in `.github/release-notes/<version>.md` and collect pending changes in `.github/release-notes/UNRELEASED.md`. Publish through the Release workflow only when the author asks.
- Describe unpublished features as unreleased, without inventing intermediate release numbers. Preserve historical test build labels as such; do not present them as published releases.
- Run checks relevant to the change. `./gradlew build` is the baseline and includes the standalone Paper plugin. Report exactly which tests ran and which environments were not checked.
- Exercise new wire messages through the actual protocol codec on both sides before deployment. Test code that creates game entities on the local Paper server before deploying it to players.
- When reading new Paper config values, account for older deployed configs that may lack the keys.

## Product boundaries

- Keep gameplay close to vanilla and fair for every player. The client mod is mandatory; the server owns gameplay rules, rewards, and validation.
- Preserve the module API's ability to customize game behavior and expose game events. The target Minecraft version is pinned to 26.2; version-specific hooks are acceptable when verified.
- Prefer focused first-party solutions. Add third-party dependencies only when there is a concrete need.
- Keep code efficient. Avoid per-tick work when an event or cached result suffices, and keep I/O and database operations off the main game thread.
- DUM-Visuals is a separate mod and is outside this repository's scope.
- Keep signing private keys off the public repository and the VDS. Development signatures are only for development clients.

## Deployment

- Deploy to the live VDS only after the author explicitly asks for it.
- Do not restart the live server while players are online without the author's approval.
- Verify changes on a local test server first. For a live plugin update, follow the established staged jar replacement and plugin reload process.
