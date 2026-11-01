# SDK protocol

SDK 0.14.2 uses protocol 5. SDK 0.14.1 uses protocol 4. Update both client and Paper together for this wire change.

Unreleased development uses protocol 6 for runtime failure reports. The published SDK version stays 0.14.2 until a release is requested. Use matching development client/Paper builds; published protocol 5 clients cannot join a protocol 6 server. This is not a published SDK release.

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

## Runtime failure reports (unreleased, protocol 6)

`MODULE_FAILURE` (type 8, client to server in play) contains three modified-UTF strings: module ID, callback context (1-128 characters), and diagnostic detail (1-1024 characters). IDs must be valid/nonempty; context/detail must be nonblank single lines without control characters or Unicode line separators. Both encoding and decoding validate them and reject trailing/truncated data. Error code `MODULE_RUNTIME` has stable ID 18. Installation messages and the stable HELLO prefix keep their framing.

The SDK reports the first managed runtime failure before deactivating the client session and cleaning up every module. This includes optional modules and cold mod callbacks registered through the context. Paper accepts a report only for a module granted to that player in the current catalog epoch, atomically revokes all grants, logs the bounded detail and fires `ClientModuleFailureEvent` on the main thread before disconnecting with `runtime.failed` and the stable code. Raw client context/detail are not rendered in the kick message. Duplicate, stale-epoch and ungranted reports are ignored. Reports describe client claims, not verified execution or an anti-cheat guarantee.

The client normally waits for Paper's configured kick message. A three-second fallback disconnects locally if the server does not respond; its callback is bound to the original connection and cannot disconnect a later session. Disconnect ownership is retained for normal cleanup even though effects are already inactive. Fresh contexts are created on rejoin. Optional startup failures retain their existing installation behavior; they do not become play-phase reports. A terminated JVM or an exception outside SDK-managed callbacks cannot reliably send this report.
