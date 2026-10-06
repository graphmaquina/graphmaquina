# Release signing

GraphMaquina releases use a dedicated RSA 4096-bit OpenPGP signing key. Its public identity is
`Hugo G <60438622+0xhug0@users.noreply.github.com>`.

```text
851F 7594 9D70 884A C95A AB56 455A D78F 0EC0 B9F1
```

The full fingerprint identifies the key; an email address or short key ID alone does not. The public key
is distributed through `hkps://keyserver.ubuntu.com`. The private key is protected with a generated
passphrase. The release maintainer stores the encrypted private key, passphrase, public key, fingerprint
and revocation certificate together in 1Password. None of the private material belongs in this repository.

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
VERSION=1.0.0   # the version you are verifying
BASE=https://repo.maven.apache.org/maven2/io/github/graphmaquina/graphmaquina-core/$VERSION
curl --fail --remote-name "$BASE/graphmaquina-core-$VERSION.jar"
curl --fail --remote-name "$BASE/graphmaquina-core-$VERSION.jar.asc"
gpg --verify "graphmaquina-core-$VERSION.jar.asc" "graphmaquina-core-$VERSION.jar"
```

These download commands require the release to be publicly available. Verify the reported full fingerprint
against the value above. GnuPG may warn that the key is not certified by a trusted signature; that concerns
your local trust database. A bad signature or a different fingerprint is a separate problem and must not
be ignored.

## Sign using 1Password CLI

The build reads `SIGNING_KEY` and `SIGNING_PASSWORD`. The first is an ASCII-armored private key, not a path.
The signing item has `Private PGP key` and `password` fields. 1Password shows the first under two names, the
id `private_key` and the label `Private PGP key`; they are one field, and the commands below address it by
its label. Replace the placeholders with your vault and item names or IDs after signing in to 1Password CLI:

```bash
./gradlew --no-daemon test :graphmaquina-gradle-plugin:test

SIGNING_KEY='op://<vault>/<item>/Private PGP key' \
SIGNING_PASSWORD='op://<vault>/<item>/password' \
op run -- ./gradlew --no-daemon --no-configuration-cache centralBundle
```

The resulting `build/graphmaquina-central-bundle.zip` contains six modules and the Gradle plugin marker.
The build checks metadata, required files and checksums. Before uploading, independently verify every
signature with GnuPG. The ZIP contains public artifacts and detached signatures; it must never contain
private keys or the passphrase. Upload it through Central Portal, wait for validation, then publish and
confirm the artifacts can be resolved from Maven Central.

For key recovery, import the encrypted backup directly from 1Password without writing it into the checkout:

```bash
op read 'op://<vault>/<item>/Private PGP key' | gpg --import
```

Keep the revocation certificate available in the vault for a lost or compromised key. Do not import or
publish that certificate during normal setup: revocation is an exceptional recovery operation. Check key
expiration before each release and distribute updated public key material after renewing it.
