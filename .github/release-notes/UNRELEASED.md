# Unreleased

## Module publication

- `/dumsdk reload` no longer disconnects players in game. Each player whose catalog changed gets the new catalog in play; the client downloads the changed modules, turns off the old ones (and the modules that depend on them), starts the new ones and reloads module resources without reconnecting. Unchanged modules keep running. A changed server-scoped Fabric mod still disconnects the player with a request to restart the game; a failed or timed-out update disconnects with the usual installation error.
- New Paper event `ClientModulesLoadedEvent`: modules that just started on a player's client, once on join (during `PlayerJoinEvent`) and again for the modules an update swapped. Server sides that send their module a state should send it here.
- Modules signed with the dev key are left out of the catalog with a warning, and players join without them, unless the new config option `allow-dev-signed: true` is set. A live server can no longer offer a jar that players' clients refuse.

## Diagnostics

- The client sends its own Fabric mods (top-level, as "id version") in HELLO. Paper logs them on join, with installation and update failures and with runtime failure reports; `ClientModuleFailureEvent.clientMods()` exposes them.

## Versions and updates

- The mod and the plugin must have exactly the same version. A player with another version sees a window with both versions and the buttons Cancel and Update instead of a disconnect message. Only after Update does the mod download exactly the server's release from GitHub, check its signature and replace itself when the game closes; the window offers to close the game.
- The mod no longer downloads updates by itself at game start. Check for updates in the DUM-SDK menu still installs the latest release on request.
- Versions carry their stage: `X.Y.Z-alpha` and `X.Y.Z-beta` (optionally `.N`) before the release `X.Y.Z`, in the tag, the file names and the release title (`DUM-SDK 0.16.0 Alpha`, `DUM-SDK 0.16.0 Release`).

## Upgrade

- Protocol 7 and exact versions: clients and the Paper plugin must be updated together, to the same version. Protocol 6 clients (0.15.x) are refused by a protocol 7 server with the usual outdated-client message.
- The publication messages are new keys (`core.updated`, `core.updated-selected`), so existing `messages.yml` files show the new text from the jar. A development server that uses dev-signed modules needs `allow-dev-signed: true` in `plugins/DUM-SDK/config.yml`.
