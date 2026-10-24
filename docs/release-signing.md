# Release signing

GraphMaquina releases use a dedicated RSA 4096-bit OpenPGP signing key. Its public identity is
`Hugo G <60438622+0xhug0@users.noreply.github.com>`.

```text
851F 7594 9D70 884A C95A AB56 455A D78F 0EC0 B9F1
```

The full fingerprint identifies the key; an email address or short key ID alone does not. The public key
is distributed through `hkps://keyserver.ubuntu.com`.

## Retrieve the public key and verify a release

Install GnuPG, then retrieve the key by its full fingerprint:

```bash
brew install gnupg  # macOS; use your platform's package manager elsewhere
gpg --keyserver hkps://keyserver.ubuntu.com \
    --recv-keys 851F75949D70884AC95AAB56455AD78F0EC0B9F1
gpg --fingerprint 851F75949D70884AC95AAB56455AD78F0EC0B9F1
```

Download an artifact and its adjacent `.asc` signature from Maven Central, then verify both together:

```bash
VERSION=x.y.z   # the version you are verifying
BASE=https://repo.maven.apache.org/maven2/io/github/graphmaquina/graphmaquina-core/$VERSION
curl --fail --remote-name "$BASE/graphmaquina-core-$VERSION.jar"
curl --fail --remote-name "$BASE/graphmaquina-core-$VERSION.jar.asc"
gpg --verify "graphmaquina-core-$VERSION.jar.asc" "graphmaquina-core-$VERSION.jar"
```

These download commands require the release to be publicly available. Verify the reported full fingerprint
against the value above. GnuPG may warn that the key is not certified by a trusted signature; that concerns
your local trust database. A bad signature or a different fingerprint is a separate problem and must not
be ignored.

---

Next: [Documentation illustrations](images/README.md). Back to the [documentation index](README.md).
