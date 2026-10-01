# Releasing

Most of this is in the files already — the `release` profile in `pom.xml` says what
is published and how, and `.github/workflows/release.yml` says what a tag does. What
follows is the part that is not in either, and the order to do it in.

## The steps

1. Bump the version in `pom.xml`, `parsers/pom.xml`, `owltx/pom.xml`, `owltx/VERSION`
   and the dependency snippet in `README.md`. Nothing reads `owltx/VERSION` during the
   build, so it is the one that drifts — the release workflow fails if it disagrees
   with the tag.
2. Open a PR and let the checks pass. `main` is protected: no direct pushes, and the
   four `build` jobs plus `cli` must be green. No approving review is required, so you
   can merge your own.
3. Merge, then tag the merge commit and push the tag:
   `git tag -a v0.5.0 -m "DLe 0.5.0" && git push origin v0.5.0`
4. The `release` workflow builds the tag, checks the artifacts carry the version the
   tag claims, runs the CLI, and publishes the GitHub release with `owltx-<v>.jar` and
   `dlextended-parsers-<v>.jar` attached. Download the jar and run it — the point of
   attaching it is that someone will.
5. Then, and only then, publish to Maven Central:

   ```
   mvn -P release clean deploy
   ```

## Two things that will bite

**The gpg passphrase is not cached in a non-interactive shell.** Sign anything once
first so the agent holds it, or pass `-Dgpg.pinentryMode=loopback` and let Maven
prompt. Never `-Dgpg.passphrase=…`: it goes into shell history and the process list.
There are two secret keys — the 2023 ed25519 one is revoked — and `gpg.conf` sets
`default-key` to the rsa4096 one, so the right key is already chosen.

**Maven Central is permanent.** A published version cannot be replaced or withdrawn.
The tag and the GitHub release can be redone; Central cannot. So before step 5:

```
mvn -P release clean verify
```

That builds the sources and javadoc jars and signs everything, and uploads nothing.

## What goes where

`dlextended-parsers` is the library and the only thing published to Central. `owltx`
is the command-line tool and is skipped there — it is shaded, with OWLAPI, ANTLR and
HttpClient inside it, which is what makes it runnable and exactly what a consumer
resolving it from Central should not receive. It ships as an attachment on the GitHub
release instead.
