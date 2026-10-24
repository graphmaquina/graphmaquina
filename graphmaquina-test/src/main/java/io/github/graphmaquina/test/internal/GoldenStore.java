package io.github.graphmaquina.test.internal;

import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.snapshot.GoldenOptions;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import lombok.NonNull;

/**
 * The two modes of a golden. Read mode compares the expected text of a class-path resource, found through the
 * {@link ClassLoader} of the options, against the text a test has just produced, and never writes. Update mode
 * never consults the class path: it works on the source file under the source root of the options, reads it
 * only to see whether it already holds the new text, and rewrites it when it does not. A stale copy of the
 * golden on the class path therefore cannot make a current source be rewritten, and an I/O failure while
 * reading the source never turns itself into a write.
 *
 * <p>A write goes to a temporary file beside the file it replaces and is then moved over that file,
 * atomically where the file system supports it, so a reader never sees a half-written golden and no global
 * lock is needed. That temporary file is created like any other new file, so a regenerated golden carries the
 * permissions the platform gives a file the build writes rather than an owner-only temporary's. Two
 * expectations that name the same resource still race with each other: give every expectation a resource
 * of its own.
 *
 * <p>A golden is read as UTF-8 without a leading byte order mark, which an editor may add and a diff cannot
 * show, and a resource that names a directory is not a golden. In update mode a source that cannot be decoded
 * is simply a different file, so it is replaced instead of reported. A symbolic link inside the root that leads
 * inside it, the source itself or a directory above it, is written through even when what it names does not
 * exist yet, as long as no {@code .} or {@code ..} follows a missing name: the link stays, the file it leads to
 * is the one written, with any directory missing on the way, and the reported path is the one that was named.
 */
public final class GoldenStore {

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private GoldenStore() { }

    /**
     * @param resource the class-path resource holding the expected text, with {@code /} as its separator
     * @param actual the text the test has produced
     * @param diff renders the difference between expected and actual for the failure message
     * @throws GraphAssertionError in read mode when the resource is missing, names a directory or differs
     *     from {@code actual}
     * @throws IllegalArgumentException if the resource name is not a relative, slash-separated path, or, in
     *     update mode, if the file it names resolves outside the source root
     * @throws IllegalStateException if the golden cannot be read or written
     */
    public static void assertMatches(String resource, String actual, GoldenOptions options,
            BiFunction<String, String, String> diff) {
        validateResource(resource);
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(diff, "diff");
        if (options.update()) {
            update(resource, actual, options);
        } else {
            compare(resource, actual, options, diff);
        }
    }

    private static void compare(String resource, String actual, GoldenOptions options,
            BiFunction<String, String, String> diff) {
        String expected = normalize(read(resource, options));
        String produced = normalize(actual);
        if (expected.equals(produced)) {
            return;
        }
        throw new GraphAssertionError(resource, "content", "matching golden",
                diff.apply(expected, produced), updateHint("regenerate it"));
    }

    private static String read(String resource, GoldenOptions options) {
        requireNotADirectory(resource, options.loader());
        try (InputStream stream = options.loader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new GraphAssertionError(resource, "content", "an existing golden resource",
                        "missing; " + updateHint("write it"));
            }
            return decode(resource, stream.readAllBytes());
        } catch (IOException failure) {
            throw new IllegalStateException("the golden resource <" + resource + "> could not be read",
                    failure);
        }
    }

    /**
     * A class-path directory is answered with a stream of its entry names, which the comparison would take
     * for the golden. Only a directory on the file system is told apart; any other URL is read as before.
     */
    private static void requireNotADirectory(String resource, ClassLoader loader) {
        URL location = loader.getResource(resource);
        if (location == null || !"file".equals(location.getProtocol())) {
            return;
        }
        try {
            if (Files.isDirectory(Path.of(location.toURI()))) {
                throw new GraphAssertionError(resource, "content", "an existing golden resource", "a directory");
            }
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException unlocatable) {
            // not a path this class can inspect: leave it to the read, which reports what it finds
        }
    }

    /**
     * How to turn update mode on, named by both read failures so a consumer never has to look it up. A
     * mismatch carries it in the message alone, so {@code actual()} stays the difference and nothing else.
     */
    private static String updateHint(String purpose) {
        return "run with -D" + GoldenOptions.UPDATE_PROPERTY + "=true -D" + GoldenOptions.ROOT_PROPERTY
                + "=<source root> to " + purpose;
    }

    private static String decode(String resource, byte[] bytes) {
        try {
            return decodeStrictly(bytes);
        } catch (CharacterCodingException failure) {
            throw new IllegalStateException("the golden <" + resource + "> is not valid UTF-8", failure);
        }
    }

    /** Strict UTF-8, minus one leading byte order mark: an editor adds it, and it is invisible in a diff. */
    private static String decodeStrictly(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    }

    /** A source that cannot be decoded differs from any text written here, so update mode replaces it. */
    private static boolean existingMatches(byte[] existing, String actual) {
        try {
            return normalize(decodeStrictly(existing)).equals(normalize(actual));
        } catch (CharacterCodingException undecodable) {
            return false;
        }
    }

    private static void update(String resource, String actual, GoldenOptions options) {
        Path root = options.sourceRoot().orElseThrow(() -> new IllegalStateException("update mode requires a"
                + " source root; the golden <" + resource + "> has nowhere to be written"));
        Path target = root.resolve(resource).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("the resource <" + resource + "> escapes the source root <"
                    + root + ">");
        }
        try {
            requireInside(resource, root, target);
            // A link inside the root, dangling or not, is a golden shared under another name: write the file it
            // leads to, so the link survives, and report the name the test gave.
            Path destination = reach(target);
            if (Files.isRegularFile(destination) && existingMatches(Files.readAllBytes(destination), actual)) {
                return;
            }
            write(destination, actual);
        } catch (IOException failure) {
            throw new IllegalStateException("the golden <" + resource + "> could not be written to <"
                    + target + ">", failure);
        }
        options.reporter().accept(target);
    }

    /**
     * Refuses a source root that is not a directory, and a target that leaves the real source root through a
     * symbolic link, dangling or not, whether the link is the target itself or an existing ancestor of it under
     * the root.
     */
    private static void requireInside(String resource, Path root, Path target) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("the source root <" + root + "> is not a directory");
        }
        Path real = root.toRealPath();
        for (Path existing = target; existing != null && existing.startsWith(root);
                existing = existing.getParent()) {
            if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS) && !reach(existing).startsWith(real)) {
                throw new IllegalArgumentException("the resource <" + resource + "> resolves through <" + existing
                        + "> outside the source root <" + real + ">");
            }
        }
    }

    /**
     * Where a write of {@code path} lands: its real path when it has one, otherwise the real path of the part
     * that exists, reached through every symbolic link on the way, dangling ones included, followed by the
     * names still missing, which the write creates. The file system resolves no {@code .} or {@code ..} under
     * a missing name, so such a path fails as missing: a {@code ..} kept there would let the write leave the
     * root unseen by the containment check.
     */
    private static Path reach(Path path) throws IOException {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException missing) {
            Path parent = path.getParent();
            String name = String.valueOf(path.getFileName());
            if (parent == null || ".".equals(name) || "..".equals(name)) {
                throw missing;
            }
            if (Files.isSymbolicLink(path)) {
                return reach(parent.resolve(Files.readSymbolicLink(path)));
            }
            return reach(parent).resolve(path.getFileName());
        }
    }

    private static void write(Path target, String actual) throws IOException {
        Files.createDirectories(target.getParent());
        // Not createTempFile: on POSIX it creates the file owner-only and the move preserves that, so every
        // regenerated golden would land at 0600 instead of the permissions a newly written file gets.
        Path temporary = target.resolveSibling(target.getFileName() + "."
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), Character.MAX_RADIX)
                + ".tmp");
        try {
            Files.writeString(temporary, actual, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Rejects a name that is not a relative, slash-separated class-path resource, so that update mode can
     * never be steered out of its source root. A caller that guards a null subject validates the name first,
     * because a malformed name is an error of the calling test either way.
     */
    public static void validateResource(@NonNull String resource) {
        if (resource.isEmpty()) {
            throw new IllegalArgumentException("the resource name is empty");
        }
        if (resource.charAt(0) == '/') {
            throw new IllegalArgumentException("the resource <" + resource + "> must be relative");
        }
        if (resource.indexOf('\\') >= 0 || resource.indexOf((char) 0) >= 0) {
            throw new IllegalArgumentException("the resource <" + resource
                    + "> must use / as its only separator and carry no NUL");
        }
        if (resource.length() >= 2 && Character.isLetter(resource.charAt(0)) && resource.charAt(1) == ':') {
            throw new IllegalArgumentException("the resource <" + resource + "> carries a drive prefix");
        }
        for (String segment : resource.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("the resource <" + resource
                        + "> carries an empty or relative segment");
            }
        }
    }
}
