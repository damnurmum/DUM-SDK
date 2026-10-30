# Release notes

Keep the published description of version X.Y.Z in `.github/release-notes/X.Y.Z.md`. GitHub Actions uses that file verbatim as the release body. `0.14.0.md` documents the already published release; its binaries are not rebuilt or replaced.

Collect new changes in `UNRELEASED.md`. Only when the author requests a release:

1. Choose the version and update `version` in `gradle.properties`.
2. Move the accumulated notes into `X.Y.Z.md`, give them the release title and describe changes, upgrade steps and validation. Start a fresh `UNRELEASED.md` for later work.
3. Commit and push the release changes to `main`.
4. Run the **Release** workflow on `main`, or push tag `vX.Y.Z` pointing to that exact commit.

Normal branch pushes and pull requests only run Build. Release never increments versions itself, refuses a tag/version mismatch or missing notes, and does not overwrite existing releases. A failed run may leave a draft; inspect and remove that draft before retrying the same version. Existing tags must point to the checked-out commit.

The repository Actions secret `DUM_SIGNING_KEY` contains the base64 PKCS#8 Ed25519 private key matching `Signing.RELEASE_KEY`. The workflow exposes it only to the artifact preparation step; no key file is uploaded. Publication uses the built-in `GITHUB_TOKEN` with `contents: write`, not a personal token.

`./gradlew --no-daemon prepareRelease` builds and tests all projects, verifies the signature against the public release key, and writes the client, its signature, the Paper plugin and SHA-256 sums into `build/release`. It does not publish anything. Local signing uses `DUM_SIGNING_KEY`, `-PdumSigningKey=<file>` or `~/.dum/signing.key`.
