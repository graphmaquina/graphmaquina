# Build, verify and release this repository

This page is for maintainers of GraphMaquina itself: the commands that verify the repository, the checks that run
before every compilation, and the release to Maven Central. Applications that resolve a release from Maven Central
need none of it.

## Contents

- [Verification commands](#verification-commands)
- [Size and complexity checks](#size-and-complexity-checks)
- [Documentation check](#documentation-check)
- [Release to Maven Central](#release-to-maven-central)

## Verification commands

These commands run from the GraphMaquina checkout, not the ticket application's directory:

```bash
./gradlew --no-daemon build
./gradlew :graphmaquina-core:publishMavenPublicationToAdapterTestsRepository \
          :graphmaquina-codegen:publishMavenPublicationToAdapterTestsRepository \
          :graphmaquina-processor:publishMavenPublicationToAdapterTestsRepository
./gradlew --no-daemon test
./gradlew --no-daemon :graphmaquina-gradle-plugin:test
./gradlew --no-daemon :graphmaquina-test:publishedArtifactTest --offline
./gradlew :graphmaquina-example:test
./gradlew :graphmaquina-codegen:test :graphmaquina-processor:test
./gradlew :graphmaquina-maven-plugin:test
```

Stage the sibling artifacts before running the Gradle adapter's TestKit suite, especially in a fresh checkout
or after changing versions. This uses a file repository under `build/adapter-test-repository`.
Run that suite in an invocation of its own, never together with `test` or `build`. The Maven adapter's suite,
which `test` runs, and `:graphmaquina-test:publishedArtifactTest`, which `build` reaches, first republish core, the
generator and the processor into that repository, rewriting their files in place while the TestKit builds of that
suite read them. The Gradle adapter's plugin is a separate included build, while the Maven adapter's integration
suite drives actual Maven builds. Have `mvn` available to run it.
The example applies the included plugin and uses the sibling generator project.

`./gradlew test` runs the ordinary suites for core, processor, codegen, example, Maven adapter and testing
support. In `graphmaquina-test`, it excludes `PublishedArtifactSmokeTest`, `ReadmeQuickstartTest`,
`GuideOutcomeFragmentsTest` and `TestingGuideFragmentsTest`. `./gradlew build` reaches all four through `check` →
`:graphmaquina-test:publishedArtifactTest`, which stages core, testing support, generator, processor, Maven
plugin, Gradle plugin and its marker into the local file repository.
The published-artifact task also runs independently with the command above. Its external README consumer
resolves dependencies offline, so third-party dependencies must already be cached.

The included Gradle adapter's suite requires the explicit `:graphmaquina-gradle-plugin:test` command;
`build` does not run it. Ordinary test reports are under each module's `build/reports/tests/test`, and the
artifact smoke reports are under `graphmaquina-test/build/reports/tests/publishedArtifactTest`.

## Size and complexity checks

Every main and test compilation first runs the size and complexity checks in `config/checkstyle/` on the
hand-written `src/main/java` and `src/test/java` of each module and of the Gradle plugin build: `main.xml` for
production code and `test.xml` for test code. Both profiles limit a method to 30 statements and 50 lines, with
blank and comment lines not counted, and every line to 120 characters, except `package` and `import` lines and
lines that begin, after their indentation, with `*`, `//` or `/*`. A line that begins with a block comment
followed by code on the same line is checked. Production code is also limited to a
cyclomatic complexity of 15 per method, a class fan-out of 30 and 400 statements per class and per file; test
code to 5, 40 and 500. Both profiles also limit a record to 150 statements. A violation stops the build before
`javac` runs and prints its file and line. The fix is to split the code into smaller methods or classes, or to
wrap a long line: the rules honor no `@SuppressWarnings`, no `// CHECKSTYLE:OFF` comment and no exemption
list.

The Gradle plugin is a separate included build that the root build compiles first, so a violation in its main
sources stops the root build before any module is checked. While such a violation stands, every Gradle command of
the root build, including `help` and the release commands below, stops at the Gradle plugin's violations. The
command below lists every violation in that build, main and test, at once:

```bash
./gradlew -p graphmaquina-gradle-plugin checkstyleMain checkstyleTest --continue
```

## Documentation check

Every Java compilation of the root build's modules also first runs `checkDocs`, the documentation check, over
every Markdown file the repository versions, wherever it is: each `*.md` that git tracks, or that is new and no ignore
rule covers, so a new page is checked before it is added. git must be on the `PATH`, or the check stops the build;
in a checkout without `.git` it is not needed, and every `*.md` outside the `build` and `.gradle` directories is
checked. A page has at most 500 lines and 40 960 bytes; a page over 100 lines has, before its first `##` heading
other than `## Contents`, a list whose unindented entries link every other `##` heading in order, each entry with its
heading's text; a section, from one heading to the next, has at
most 150 lines; a page has exactly one `#` heading, as its first, and no heading more than one level below the one
before it; `docs/README.md` links every other page except the root `README.md`; and every link relative to its page,
and every anchor, resolves (a root-relative link such as `/docs/guide.md` is reported). The links are checked by
[lychee](https://github.com/lycheeverse/lychee) 0.24, which must be on the `PATH`
(`cargo install lychee --locked --version 0.24.2`, `brew install lychee` while Homebrew ships 0.24, or the 0.24.2
release binary); without it, or with another version, the check stops the build and says so. A violation stops the
build with its file and line. The fix is to split the page or section, add the contents list or the index link, or
fix the heading or link, never to raise a limit. The check's own tests run whenever `buildSrc` is built; those that
need lychee are skipped unless lychee 0.24 is installed. The check runs alone with `./gradlew checkDocs`.

## Release to Maven Central

Releasing is a maintainer operation. The version comes from git tags: both builds read `-PgraphmaquinaVersion`
when a release passes it and otherwise derive it with `git describe --tags`, so no file in the repository states
a release version and a release creates a tag and edits no file. On a tagged commit the version is the tag
without its leading `v`, so tag `vX.Y.Z` builds as `X.Y.Z`, even with uncommitted changes; on any other commit it is the most recent tag followed by the commits since it and the abbreviated
commit, as `X.Y.Z-12-g03952fd`, so a development build never takes the coordinates of a published release, and
`centralBundle` refuses such a version. A checkout without `.git`, or without tags, builds as `0.0.0-dev`, which
`centralBundle` refuses too, as it refuses a `-SNAPSHOT`. Pass the flag to stage another version. A published version can never be
replaced or deleted.

To build and check a bundle without publishing it, configure `SIGNING_KEY` with the project's ASCII-armored
private release key and `SIGNING_PASSWORD` with its passphrase, then run:

```bash
./gradlew --no-daemon centralBundle
```

Maintainers using 1Password CLI can inject both secrets for this command without copying them into the
checkout or shell history. Replace `<vault>` and `<item>` with the vault and signing item's names or IDs; the
item must have a `Private PGP key` field containing the armored secret key and a `password` field. 1Password
shows that field under two names, the id `private_key` and the label `Private PGP key`; they are one field, and
these examples address it by its label:

```bash
SIGNING_KEY='op://<vault>/<item>/Private PGP key' \
SIGNING_PASSWORD='op://<vault>/<item>/password' \
op run -- ./gradlew --no-daemon --no-configuration-cache centralBundle
```

The `op://` values are references, not credentials. `op run` resolves them into the child process's
environment and masks secrets in its output. Keep the private key, passphrase and revocation certificate
in the vault. Only distribute the public key. See [release signing](release-signing.md) for the
public fingerprint, key retrieval and verification commands.

The task stages the five library and Maven publications plus the Gradle plugin and its marker, validates the
expected artifacts, POM metadata, signature files and MD5/SHA-1 checksums, then produces:

```text
build/graphmaquina-central-bundle.zip
```

Central staging refuses to run without `SIGNING_KEY`; local and adapter-test repositories still support unsigned
development builds. The ZIP contains only the current release's expected files, excluding repository metadata
and older releases. The task uploads nothing: only once it succeeds is the ZIP ready to go to Central Portal,
where a maintainer uploads it with the authorized namespace and release credentials. Portal validation,
namespace ownership and availability of the public signing key remain external requirements; the local
structural checks do not replace cryptographic signature verification or Portal validation.

Do not publish a bundle signed with a temporary test key.

---

Next: [Release signing](release-signing.md). Back to the [documentation index](README.md).
