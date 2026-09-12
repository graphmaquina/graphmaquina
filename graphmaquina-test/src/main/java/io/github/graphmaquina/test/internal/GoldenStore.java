package io.github.graphmaquina.test.internal;

import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.snapshot.GoldenOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/**
 * The two halves of a golden: comparing the expected text of a class-path resource against the text a test
 * has just produced, and rewriting the source file that resource was built from. The halves never mix —
 * reading resolves only through the {@link ClassLoader} of the options and writing only under their source
 * root — so update mode cannot overwrite a build output and a failed read never turns itself into a write.
 *
 * <p>A write goes to a temporary file in the target directory and is then moved over the target, atomically
 * where the file system supports it, so a reader never sees a half-written golden and no global lock is
 * needed. That temporary file is created like any other new file, so a regenerated golden carries the
 * permissions the platform gives a file the build writes rather than an owner-only temporary's. Two
 * expectations that name the same resource still race with each other: give every expectation a resource
 * of its own.
 */
public final class GoldenStore {

    private GoldenStore() { }

    /**
     * @param resource the class-path resource holding the expected text, with {@code /} as its separator
     * @param actual the text the test has produced
     * @param diff renders the difference between expected and actual for the failure message
     * @throws GraphAssertionError in read mode when the resource is missing or differs from {@code actual}
     * @throws IllegalArgumentException if the resource name is not a relative, slash-separated path
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
     * How to turn update mode on, named by both read failures so a consumer never has to look it up. A
     * mismatch carries it in the message alone, so {@code actual()} stays the difference and nothing else.
     */
    private static String updateHint(String purpose) {
        return "run with -D" + GoldenOptions.UPDATE_PROPERTY + "=true -D" + GoldenOptions.ROOT_PROPERTY
                + "=<source root> to " + purpose;
    }

    private static String decode(String resource, byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalStateException("the golden <" + resource + "> is not valid UTF-8", failure);
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
            if (Files.isRegularFile(target)
                    && normalize(decode(resource, Files.readAllBytes(target))).equals(normalize(actual))) {
                return;
            }
            write(target, actual);
        } catch (IOException failure) {
            throw new IllegalStateException("the golden <" + resource + "> could not be written to <"
                    + target + ">", failure);
        }
        options.reporter().accept(target);
    }

    /** Rejects a target whose nearest existing ancestor leaves the real source root through a symbolic link. */
    private static void requireInside(String resource, Path root, Path target) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("the source root <" + root + "> is not a directory");
        }
        Path real = root.toRealPath();
        Path existing = target;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null && !existing.toRealPath().startsWith(real)) {
            throw new IllegalArgumentException("the resource <" + resource + "> resolves through <" + existing
                    + "> outside the source root <" + real + ">");
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
    public static void validateResource(String resource) {
        Objects.requireNonNull(resource, "resource");
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
