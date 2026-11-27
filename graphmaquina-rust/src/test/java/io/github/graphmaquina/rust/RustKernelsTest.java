package io.github.graphmaquina.rust;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The raw contract of the JNI entry points: what each one reads, writes and returns, and the Java exception each
 * refusal becomes. Nothing here may crash the JVM: a null array or a malformed graph comes back as an exception.
 */
class RustKernelsTest {

    @Test
    void theEmptyCallReturns() {
        RustKernels.noop();
    }

    @Test
    void aBorrowedArrayIsReadInPlace() {
        assertEquals(6, RustKernels.sumCritical(new int[] {1, 2, 3}));
        assertEquals(0, RustKernels.sumCritical(new int[0]));
    }

    @Test
    void anArrayCopiedOutInChunksSumsTheSame() {
        int[] values = new int[5_000];
        int expected = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 7919;
            expected += values[i];
        }
        assertEquals(expected, RustKernels.sumRegion(values));
        assertEquals(6, RustKernels.sumRegion(new int[] {1, 2, 3}));
        assertEquals(0, RustKernels.sumRegion(new int[0]));
    }

    @Test
    void componentsAreNumberedByTheirFirstDeclaredNode() {
        int[] component = new int[4];
        int count = RustKernels.strongComponents(new int[] {0, 1, 3, 4, 4}, new int[] {1, 0, 2, 3}, component);
        assertEquals(3, count);
        assertArrayEquals(new int[] {0, 0, 1, 2}, component);
    }

    @Test
    void cyclesComeBackAsLengthPrefixedRuns() {
        assertArrayEquals(new int[] {2, 0, 1, 1, 0}, RustKernels.cycles(new int[] {0, 2, 3}, new int[] {1, 0, 0}));
        assertArrayEquals(new int[0], RustKernels.cycles(new int[] {0, 1, 1}, new int[] {1}));
    }

    @Test
    void anAlignmentWritesOneKindPerStep() {
        byte[] kinds = new byte[5];
        int steps = RustKernels.align(new int[] {1, 2, 3}, new int[] {1, 3}, kinds);
        assertEquals(3, steps);
        assertArrayEquals(new byte[] {0, 1, 0}, Arrays.copyOf(kinds, steps));
    }

    @Test
    void aMalformedGraphIsRefusedWithTheRuleItBreaks() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RustKernels.strongComponents(new int[] {0, 1}, new int[] {1}, new int[1]));
        assertEquals("every target must be a node of the graph", error.getMessage());
        IllegalArgumentException cycles = assertThrows(IllegalArgumentException.class,
                () -> RustKernels.cycles(new int[] {0, 2, 1}, new int[] {0, 0}));
        assertEquals("the offsets must not decrease", cycles.getMessage());
    }

    @Test
    void anOutputArrayTooShortIsRefused() {
        IllegalArgumentException components = assertThrows(IllegalArgumentException.class,
                () -> RustKernels.strongComponents(new int[] {0, 0, 0}, new int[0], new int[1]));
        assertEquals("the output array is too small", components.getMessage());
        IllegalArgumentException kinds = assertThrows(IllegalArgumentException.class,
                () -> RustKernels.align(new int[] {1, 2}, new int[] {3}, new byte[2]));
        assertEquals("the output array is too small", kinds.getMessage());
    }

    @Test
    void anAlignmentPastAMillionCellsIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RustKernels.align(new int[999], new int[1001], new byte[2000]));
        assertEquals("the alignment table exceeds a million cells", error.getMessage());
    }

    @Test
    void aNullArrayIsAJavaExceptionAndNotACrash() {
        assertThrows(NullPointerException.class, () -> RustKernels.sumCritical(null));
        assertThrows(NullPointerException.class, () -> RustKernels.sumRegion(null));
        assertThrows(NullPointerException.class, () -> RustKernels.strongComponents(null, new int[0], new int[0]));
        assertThrows(NullPointerException.class, () -> RustKernels.strongComponents(new int[] {0}, null, new int[0]));
        assertThrows(NullPointerException.class, () -> RustKernels.strongComponents(new int[] {0}, new int[0], null));
        assertThrows(NullPointerException.class, () -> RustKernels.cycles(null, new int[0]));
        assertThrows(NullPointerException.class, () -> RustKernels.align(new int[0], null, new byte[0]));
        assertThrows(NullPointerException.class, () -> RustKernels.align(new int[0], new int[0], null));
    }
}
