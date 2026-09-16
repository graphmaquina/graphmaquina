package io.github.graphmaquina.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The matrix vocabulary is gone, not deprecated: 2.0 keeps no loadable declaration of it, so a project still
 * using it fails to resolve the type instead of silently compiling against a retired contract.
 */
class RetiredVocabularyTest {

    static final List<String> RETIRED = List.of("Action", "Cell", "Cells", "Forbid", "Forbids", "Move", "Row", "Rows",
            "StateGraph", "Watch", "Watches", "When");

    @Test
    void noRetiredAnnotationIsStillOnTheClasspath() {
        for (String name : RETIRED) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("io.github.graphmaquina.annotation." + name), name);
        }
    }

    /** The DSL entry point keeps that name; only the annotation of the same name was retired. */
    @Test
    void theDslEntryPointIsNotTheRetiredAnnotation() {
        assertEquals("io.github.graphmaquina.dsl.StateGraph", io.github.graphmaquina.dsl.StateGraph.class.getName());
    }
}
