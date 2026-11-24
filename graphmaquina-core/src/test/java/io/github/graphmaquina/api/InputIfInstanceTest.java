package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code inputIfInstance(type)} answers the input when it is an instance of {@code type}, the same reference, and is
 * empty for an input of another type and for no input; it refuses a primitive token and a null type, as
 * {@code input(type)} does.
 */
class InputIfInstanceTest {

    private static final String VALUE = new String("value");

    private static OperationInputDefaultsTest.PlainGuard guardWith(Object input) {
        return new OperationInputDefaultsTest.PlainGuard() {
            @Override
            public Optional<?> input() {
                return Optional.of(input);
            }
        };
    }

    private static OperationInputDefaultsTest.PlainAction actionWith(Object input) {
        return new OperationInputDefaultsTest.PlainAction() {
            @Override
            public Optional<?> input() {
                return Optional.of(input);
            }
        };
    }

    @Test
    void anInputOfTheTypeIsPresent() {
        assertSame(VALUE, guardWith(VALUE).inputIfInstance(String.class).orElseThrow());
        assertSame(VALUE, actionWith(VALUE).inputIfInstance(CharSequence.class).orElseThrow());
    }

    @Test
    void anInputOfAnotherTypeIsEmpty() {
        assertTrue(guardWith(VALUE).inputIfInstance(Integer.class).isEmpty());
        assertTrue(actionWith(VALUE).inputIfInstance(Integer.class).isEmpty());
    }

    @Test
    void noInputIsEmpty() {
        assertTrue(new OperationInputDefaultsTest.PlainGuard().inputIfInstance(String.class).isEmpty());
        assertTrue(new OperationInputDefaultsTest.PlainAction().inputIfInstance(String.class).isEmpty());
    }

    @Test
    void aPrimitiveTokenIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> guardWith(1).inputIfInstance(int.class));
        assertThrows(IllegalArgumentException.class, () -> actionWith(1).inputIfInstance(int.class));
    }

    @Test
    void aNullTypeIsRefused() {
        assertEquals("type is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> guardWith(VALUE).inputIfInstance(null)).getMessage());
        assertEquals("type is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> actionWith(VALUE).inputIfInstance(null)).getMessage());
    }
}
