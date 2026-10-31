# Publication fixtures

These six tiny archives contain only module metadata, without executable code.
`alpha` and `beta` are hot module IDs; `coldprobe` uses the server-scoped Fabric contract.
Each has versions 1.0.0 and 2.0.0 and a public Ed25519 signature made with the SDK's
existing development key. No private key is included or needed to run tests.

Tests copy the archives to arbitrary jar filenames to verify selection by metadata ID,
unchanged published bytes, atomic multi-selection, persistence and failed publication.
