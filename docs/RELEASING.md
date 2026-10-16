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

   Read the output rather than the exit status. Any line about skipping means
   nothing was uploaded, and the build says BUILD SUCCESS anyway — see below.
6. Confirm what arrived. Central takes a few minutes to sync:

   ```
   V=0.5.1
   B=https://repo1.maven.org/maven2/io/github/quoll/owlapi
   curl -s -O $B/dlextended-parsers/$V/dlextended-parsers-$V.jar \
           -O $B/dlextended-parsers/$V/dlextended-parsers-$V.jar.asc
   gpg --verify dlextended-parsers-$V.jar.asc dlextended-parsers-$V.jar
   ```

   And that what was published is what was tagged, which is the whole point of
   releasing from a tag at all:

   ```
   git show v$V:pom.xml | diff - <(curl -s $B/dlextended-parent/$V/dlextended-parent-$V.pom)
   ```

## Three things that will bite

**The gpg passphrase is not cached in a non-interactive shell.** Sign anything once
first so the agent holds it, or pass `-Dgpg.pinentryMode=loopback` and let Maven
prompt. Never `-Dgpg.passphrase=…`: it goes into shell history and the process list.
There are two secret keys — the 2023 ed25519 one is revoked — and `gpg.conf` sets
`default-key` to the rsa4096 one, so the right key is already chosen.

**The upload happens once, for the whole reactor.** Every module stages into one
directory and the bundle is sent at the end, so a flag meant for one module can
silence the whole release. Under plugin 0.6.0 `skipPublishing` did exactly that: it
was set on `owltx`, and 0.5.1 built a complete bundle, uploaded none of it, and
exited 0 — while still leaving `owltx` in the bundle, because skipping an upload was
never the same as excluding an artifact.

Exclusion is `excludeArtifacts` in the root pom, under every version. Note that
0.12.0 has since redefined `skipPublishing` to mean per-module exclusion, which is
what it was reached for in the first place; `excludeArtifacts` is kept because it
says what it does in one place rather than scattering flags across modules. CI
assembles a bundle on every PR and checks its contents both ways, so whatever the
flags mean next, the bundle is what gets inspected.

**Maven Central is permanent.** A published version cannot be replaced or withdrawn.
The tag and the GitHub release can be redone; Central cannot. So before step 5:

```
mvn -P release clean deploy -DskipUpload=true
unzip -l target/central-publishing/central-bundle.zip
```

Do not add `-o`. Offline, Maven never resolves the publishing extension, so the
publish goal does not run, no bundle is built, and the command still reports BUILD
SUCCESS — a dry run that checks nothing. Confirm the bundle exists rather than
trusting the exit status.

That does everything a release does — builds the sources and javadoc jars, signs
them, assembles the bundle — and sends nothing. `skipUpload` rather than
`skipPublishing`: the latter now leaves a module out of the publication altogether,
and set globally it builds no bundle at all, leaving nothing to inspect. It is a better rehearsal
than `verify`, because the bundle is the thing that gets sent, and listing it is the
only way to see what Central would receive.

Redoing them, while step 5 has not run yet: `gh release delete v0.5.1 --yes
--cleanup-tag`, re-tag the fixed commit, push. Beware `git fetch --prune-tags`, which
also deletes local-only tags that were never pushed.

## What goes where

`dlextended-parsers` is the library and the only thing published to Central. `owltx`
is the command-line tool and is excluded from the bundle — it is shaded, with OWLAPI,
ANTLR and HttpClient inside it, which is what makes it runnable and exactly what a
consumer resolving it from Central should not receive. It ships as an attachment on
the GitHub release instead.
