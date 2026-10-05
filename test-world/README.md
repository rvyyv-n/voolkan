# Test world

Fixed world for the smoke test (`docs/smoke-test.md`). The world itself is git-ignored; it is reproducible.

| Setting | Value |
|---|---|
| Folder name | `voolkan-test` |
| Seed | `20261005` |
| Game mode | creative, peaceful, no structures |
| Version | 1.21.11 |

Generate it once (this accepts the Mojang EULA on your behalf only if you pass the flag):

```powershell
tools\make-test-world.ps1 -AcceptEula
```

The harness scripts copy it to `run/saves/voolkan-test` and quick-play it:

```powershell
tools\run-lavapipe.ps1 -World voolkan-test
```

Manual fallback: create a new Creative world named `voolkan-test` with seed `20261005`, "Generate structures" off.
