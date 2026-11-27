package io.github.graphmaquina.rust;

/**
 * The JNI entry points of the Rust kernels in {@code graphmaquina-rust/native}. Every method borrows the arrays it
 * is given for the length of the call only and keeps nothing afterwards; the caller must not change those arrays from
 * another thread during the call. A refusal comes back as a Java exception: {@link NullPointerException} for a null
 * array; {@link IllegalArgumentException} for a malformed graph, an output array too short or that is also one of
 * the inputs, or an alignment past a million cells; {@link OutOfMemoryError} when the answer does not fit in the
 * native heap or in one Java array. A panic inside a kernel becomes an {@link IllegalStateException}, after Rust's
 * default panic handler has printed its message on the standard error stream.
 *
 * <p>The library is loaded once, from the path in the system property {@code graphmaquina.rust.library} when it is
 * set and from {@code java.library.path} otherwise.
 */
public final class RustKernels {

    static {
        String path = System.getProperty("graphmaquina.rust.library");
        if (path == null) {
            System.loadLibrary("graphmaquina_rust");
        } else {
            System.load(path);
        }
    }

    private RustKernels() { }

    /** Does nothing: the cost of crossing into native code and back, and nothing else. */
    public static native void noop();

    /** The wrapping sum of {@code values}, read in place: the cost of borrowing one array for a call. */
    public static native int sumCritical(int[] values);

    /** The same sum, copying the array out in chunks onto the native stack instead of borrowing it in place. */
    public static native int sumRegion(int[] values);

    /**
     * Writes the strongly connected component of each node of a CSR graph into {@code component}, numbered by the
     * lowest node of each component, and returns how many components there are. The successors of node {@code v}
     * are {@code targets[offsets[v]] .. targets[offsets[v + 1] - 1]}.
     */
    public static native int strongComponents(int[] offsets, int[] targets, int[] component);

    /**
     * Every elementary cycle of a CSR graph in the order {@code Graph.cycles()} lists them, each as its length
     * followed by its nodes, all in one array.
     */
    public static native int[] cycles(int[] offsets, int[] targets);

    /**
     * Aligns two blocks of interned lines the way {@code LineDiff} does and writes one step per entry into
     * {@code kinds}: 0 keeps a common line, 1 deletes a left line and 2 adds a right line. Returns the step count;
     * {@code kinds} must hold {@code left.length + right.length} entries.
     */
    public static native int align(int[] left, int[] right, byte[] kinds);
}
