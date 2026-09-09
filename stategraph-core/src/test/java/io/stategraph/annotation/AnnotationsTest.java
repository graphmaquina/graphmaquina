package io.stategraph.annotation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The v0.2 vocabulary, kept in core for the migration diagnostic alone. Its shape is still asserted because
 * the processor reads these very declarations to tell an unmigrated project what to write instead.
 */
@SuppressWarnings("deprecation")
class AnnotationsTest {

    static final List<Class<? extends Annotation>> ALL = List.of(StateGraph.class, Row.class, Rows.class, Action.class, Move.class,
            Cell.class, Cells.class, Forbid.class, Forbids.class, Watch.class, Watches.class, When.class, StateGraphs.class);

    @Test
    void everyAnnotationHasClassRetention() {
        for (Class<? extends Annotation> type : ALL) {
            assertEquals(RetentionPolicy.CLASS, type.getAnnotation(Retention.class).value(), type.getSimpleName());
        }
    }

    @Test
    void targetsMatchTheSpec() {
        assertArrayEquals(new ElementType[] {ElementType.TYPE}, StateGraph.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {ElementType.METHOD}, Action.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {ElementType.METHOD}, Move.class.getAnnotation(Target.class).value());
        assertArrayEquals(new ElementType[] {}, When.class.getAnnotation(Target.class).value());
        assertEquals(Rows.class, Row.class.getAnnotation(Repeatable.class).value());
        assertEquals(Cells.class, Cell.class.getAnnotation(Repeatable.class).value());
        assertEquals(Forbids.class, Forbid.class.getAnnotation(Repeatable.class).value());
        assertEquals(Watches.class, Watch.class.getAnnotation(Repeatable.class).value());
    }

    @Test
    void defaultsMatchTheSpec() throws NoSuchMethodException {
        assertEquals("status", StateGraph.class.getMethod("state").getDefaultValue());
        assertEquals("id", StateGraph.class.getMethod("id").getDefaultValue());
        assertEquals("", Action.class.getMethod("action").getDefaultValue());
        assertEquals(void.class, Action.class.getMethod("guardsBy").getDefaultValue());
        assertEquals(void.class, Move.class.getMethod("by").getDefaultValue());
        assertEquals(void.class, Cell.class.getMethod("by").getDefaultValue());
        assertEquals(void.class, Watch.class.getMethod("by").getDefaultValue());
        assertNotNull(When.class.getMethod("goTo"));
    }

    /** Every declaration of the old vocabulary is deprecated; the set entry point is not, and stays. */
    @Test
    void theOldVocabularyIsDeprecatedAndTheSetEntryPointIsNot() {
        for (Class<? extends Annotation> type : ALL) {
            assertEquals(type != StateGraphs.class, type.isAnnotationPresent(Deprecated.class),
                    type.getSimpleName());
        }
        for (Class<? extends Annotation> type : ALL) {
            if (type != StateGraphs.class) {
                assertEquals("0.3", type.getAnnotation(Deprecated.class).since(), type.getSimpleName());
            }
        }
    }
}
