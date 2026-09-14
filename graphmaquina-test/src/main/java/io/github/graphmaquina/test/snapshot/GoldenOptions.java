package io.github.graphmaquina.test.snapshot;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Where a golden is read from, whether a mismatch rewrites it, and who is told about a rewrite. Reading
 * resolves the resource through a {@link ClassLoader} only; updating writes through the source root only,
 * so a run can never overwrite a file of the class path it is reading from.
 *
 * <p>{@link #defaults()} reads the system properties {@code graphmaquina.goldens.update} and
 * {@code graphmaquina.goldens.root} at the moment it is called, so a test may set them and restore them
 * around one call. {@link #readOnly(ClassLoader)} and
 * {@link #updating(ClassLoader, Path, Consumer)} read no property at all.
 */
public final class GoldenOptions {

    /** System property selecting update mode; only the exact values {@code true} and {@code false}. */
    public static final String UPDATE_PROPERTY = "graphmaquina.goldens.update";

    /** System property holding the source root to write into; required when update mode is on. */
    public static final String ROOT_PROPERTY = "graphmaquina.goldens.root";

    private final ClassLoader loader;
    private final Path sourceRoot;
    private final boolean update;
    private final Consumer<Path> reporter;

    private GoldenOptions(ClassLoader loader, Path sourceRoot, boolean update, Consumer<Path> reporter) {
        this.loader = loader;
        this.sourceRoot = sourceRoot;
        this.update = update;
        this.reporter = reporter;
    }

    /**
     * The context class loader when the thread has one, the loader of this module otherwise, with the
     * mode and the source root taken from the system properties.
     *
     * @throws IllegalArgumentException if {@code graphmaquina.goldens.update} is neither {@code true} nor
     *     {@code false}
     * @throws IllegalStateException if update mode is on without {@code graphmaquina.goldens.root}
     */
    public static GoldenOptions defaults() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = context != null ? context : GoldenOptions.class.getClassLoader();
        boolean update = readUpdate();
        String root = System.getProperty(ROOT_PROPERTY);
        if (update && (root == null || root.isBlank())) {
            throw new IllegalStateException("update mode requires the system property " + ROOT_PROPERTY
                    + " naming the source root the goldens are written to");
        }
        Path sourceRoot = root == null || root.isBlank() ? null : root(Path.of(root));
        return new GoldenOptions(loader, sourceRoot, update, GoldenOptions::report);
    }

    /** Reads through {@code loader} and never writes. */
    public static GoldenOptions readOnly(ClassLoader loader) {
        return new GoldenOptions(Objects.requireNonNull(loader, "loader"), null, false, GoldenOptions::report);
    }

    /**
     * Reads through {@code loader} and rewrites a differing golden under {@code sourceRoot}, telling
     * {@code reporter} the absolute path of every file it writes.
     */
    public static GoldenOptions updating(ClassLoader loader, Path sourceRoot, Consumer<Path> reporter) {
        return new GoldenOptions(Objects.requireNonNull(loader, "loader"),
                root(Objects.requireNonNull(sourceRoot, "sourceRoot")), true,
                Objects.requireNonNull(reporter, "reporter"));
    }

    public ClassLoader loader() {
        return loader;
    }

    /**
     * The root update mode writes into, made absolute against the working directory and normalized; empty
     * when none was configured.
     */
    public Optional<Path> sourceRoot() {
        return Optional.ofNullable(sourceRoot);
    }

    public boolean update() {
        return update;
    }

    public Consumer<Path> reporter() {
        return reporter;
    }

    private static boolean readUpdate() {
        String value = System.getProperty(UPDATE_PROPERTY);
        if (value == null || "false".equals(value)) {
            return false;
        }
        if ("true".equals(value)) {
            return true;
        }
        throw new IllegalArgumentException("the system property " + UPDATE_PROPERTY
                + " accepts only \"true\" or \"false\", but was \"" + value + "\"");
    }

    private static Path root(Path candidate) {
        return candidate.toAbsolutePath().normalize();
    }

    private static void report(Path written) {
        System.out.println("UPDATED " + written);
    }
}
