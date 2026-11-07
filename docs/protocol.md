# SDK protocol

The unreleased SDK uses protocol 7, which adds in-game module updates and the client's mod list. SDK 0.15.x uses protocol 6 (runtime failure reports), SDK 0.14.2 protocol 5 and SDK 0.14.1 protocol 4. Update both client and Paper together for each wire change: protocol 6 clients cannot join a protocol 7 server.

The server accepts matching protocol numbers. `sdkVersion` is shown in diagnostics; it is not an enforced minimum or a comparison with the latest GitHub release. Capability negotiation is not implemented.

## Installation

HELLO retains its stable prefix: message type 0 followed by a big-endian 32-bit protocol number. The server can reject an incompatible protocol without understanding the remaining fields. Strings elsewhere use Java `DataInput/Output` modified UTF, with the codec's existing string framing.

A catalog is checked in full before requests or allocation of download buffers: valid unique module IDs (up to 64 lowercase letters, digits, `_` or `-`), nonblank versions (up to 128 characters), positive jar sizes within the existing 64 MiB per-file limit, 32-byte hashes, 64-byte signatures, unique jar hashes, and a Fabric flag encoded as exactly 0 or 1. The existing catalog count limit is 1024. Paper applies the same validation before publication.

Each requested jar accepts only nonempty chunks of up to 512 KiB, at exactly the next offset. Repeats, gaps, overlap, out-of-bounds data and unsolicited/completed hashes fail installation. SHA-256 is checked after the final chunk, then the signature, then the cached file/manifest. A cached jar must match size, hash and signature. Version rollback checks finish before any request. Each jar may be requested only once per connection; requests for unoffered jars are rejected.

The installer has explicit download/install/terminal states. Failure sends one terminal result and suppresses later chunks/completion callbacks. Successful hot installation records accepted versions only after startup/resource loading succeeds. Cold restart plans retain the existing version recording behavior.

## Results and errors

Protocol 5 READY (type 6) contains an explicit one-byte `ErrorCode` wire ID, module ID (empty for general errors) and diagnostic detail (up to 1024 characters). OK is ID 0 and requires empty module/detail fields. Enum ordinals are never serialized; assigned IDs must not be reused. Unknown IDs and malformed results are rejected.

`ErrorCode.java` lists stable codes for invalid catalogs/chunks, unexpected chunks/requests, rollback, hash/signature failures, cache I/O, module/cold mod startup, resource loading, malformed messages, unsupported protocols and handshake/install timeouts. Mismatched protocols and timeouts use the corresponding codes in their ordinary disconnect messages.

Paper logs the reported code, module and diagnostic detail. Player-facing text comes from the server's `install-errors` message keys; raw client diagnostics/markup are not rendered. A separate `handshake.error-code` text appends the stable code, with bundled defaults for old messages files.

The overall download memory budget, concurrency and bandwidth policy are unchanged. Checking a packet's correctness does not cap the combined size of many valid files or simultaneous clients. Resource budgeting remains separate work.

## Runtime failure reports (SDK 0.15.0, protocol 6)

`MODULE_FAILURE` (type 8, client to server in play) contains three modified-UTF strings: module ID, callback context (1-128 characters), and diagnostic detail (1-1024 characters). IDs must be valid/nonempty; context/detail must be nonblank single lines without control characters or Unicode line separators. Both encoding and decoding validate them and reject trailing/truncated data. Error code `MODULE_RUNTIME` has stable ID 18. Installation messages and the stable HELLO prefix keep their framing.

The SDK reports the first managed runtime failure before deactivating the client session and cleaning up every module. This includes optional modules and cold mod callbacks registered through the context. Paper accepts a report only for a module granted to that player in the current catalog epoch, atomically revokes all grants, logs the bounded detail and fires `ClientModuleFailureEvent` on the main thread before disconnecting with `runtime.failed` and the stable code. Raw client context/detail are not rendered in the kick message. Duplicate, stale-epoch and ungranted reports are ignored. Reports describe client claims, not verified execution or an anti-cheat guarantee.

The client normally waits for Paper's configured kick message. A three-second fallback disconnects locally if the server does not respond; its callback is bound to the original connection and cannot disconnect a later session. Disconnect ownership is retained for normal cleanup even though effects are already inactive. Fresh contexts are created on rejoin. Optional startup failures retain their existing installation behavior; they do not become play-phase reports. A terminated JVM or an exception outside SDK-managed callbacks cannot reliably send this report.

## In-game updates and the client's mods (unreleased, protocol 7)

HELLO keeps its stable prefix and adds the client's own Fabric mods after the SDK version: a 32-bit count (up to 1024) and that many modified-UTF lines "id version" (1-200 characters, no control characters or Unicode line separators). The client lists top-level, non-builtin mods; mods nested in another jar, like Fabric API's own modules, are left out. Paper logs the list on join, on installation and update failures and with runtime failure reports, and passes it to `ClientModuleFailureEvent`. It is a client claim, for diagnostics only.

`UPDATE` (type 9, server to client in play) carries a player's new catalog after a publication, encoded and validated like `CATALOG`. The client checks it like a catalog (including rollback), requests the jars it lacks with `REQUEST` and receives `CHUNK`s in play under the same rules as during configuration, then swaps the changed hot modules without reconnecting. It answers `UPDATED` (type 10, client to server): a 32-bit count and the IDs of the modules that started anew, the changed ones and their dependents. A failure is answered with `READY` and its error code, a change of Fabric mods with `RESTART`; Paper disconnects with the matching message in both cases, and also when no answer comes within `handshake.install-timeout-seconds`.

Paper sends `UPDATE` only to players whose catalog changed, and only one at a time: a second publication during a player's update disconnects them with the rejoin message. While the update runs, the player keeps the grant of the modules whose jars did not change, at the new catalog epoch; the full grant follows `UPDATED`, together with `ClientModulesLoadedEvent` for the started modules. A request outside a running update is ignored; a repeated or unoffered one ends the update with `UNEXPECTED_REQUEST`. The client downloads and checks update jars off the render thread and starts modules on it.

## Exact versions (unreleased, protocol 7)

The stable HELLO prefix is the type, the 32-bit protocol and the modified-UTF SDK version; WELCOME keeps the type, the protocol and the server's version. Every later protocol keeps both, so any client and server can tell their versions apart.

A server admits only a client with exactly its version, stage included (`0.16.0-alpha` is not `0.16.0`). For a client of protocol 7 or later with another version, Paper logs both versions, sends WELCOME with its own version and disconnects with `handshake.version-mismatch` and `VERSION_MISMATCH` (ID 19). The client remembers the version from WELCOME and replaces the disconnect screen with its version window; it offers to install exactly that release, found on GitHub by the tag `v<version>`, and downloads nothing without the player's consent. Clients of protocol 6 and older keep getting the outdated-client or outdated-server text.
