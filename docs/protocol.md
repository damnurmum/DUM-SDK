# SDK protocol

SDK 0.14.2 uses protocol 5. SDK 0.14.1 uses protocol 4. Update both client and Paper together for this wire change.

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
