# Release checklist

Releases, tags and pushes are the owner's call (`AGENTS.md`). This list is what to go through when the owner decides to release. Tag format: `v<modver>+<mc>` (`docs/conventions.md`).

1. Branch is merged into `main` with the owner's OK; `ROADMAP.md` is up to date (open items listed in the release notes).
2. Version bumped in `gradle.properties` (`mod_version`, e.g. `1.0.0-beta.2+1.21.11`).
3. `gradlew build` passes with JDK 21 (`docs/dev-setup.md`); jar is `build/libs/voolkan-<version>.jar`.
4. Checks: `tools\check-notices.ps1`, `tools\check-lang.ps1`, `tools\smoke-run.ps1` (no new VUIDs against `tools\baselines`), and the manual list in `docs/smoke-test.md` on the R7 if the owner can.
5. Licence files: `LICENSE`, `COPYING`, `THIRD_PARTY_NOTICES.md` and `licenses/` are in the jar; no new third-party code since the last release without a notice (never Sodium code, see `THIRD_PARTY_NOTICES.md`).
6. No AI attribution in commits, docs or the jar's metadata.
7. Tag `v<version>` on the release commit (never move or reuse a tag).
8. Push and GitHub release (pre-release while it is beta), with the jar attached and the release notes.
9. **Publish the Modrinth listing** from `docs/modrinth.md` (draft; created at release time): re-check every fact in it against the release, upload the jar, set Fabric Loader as the dependency and VulkanMod as incompatible, mark the channel (beta/release) and link the source repository. Do the owner checks at the bottom of that file first.
10. Update `ROADMAP.md` (tick the release item) and clean up: remove the worktree and branch after the merge.
