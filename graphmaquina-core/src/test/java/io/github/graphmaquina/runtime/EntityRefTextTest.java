package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AmbiguousIdentityException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.io.IOException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * How an {@link EntityRef} prints: {@code key#id}, the machine key kept whatever the id's {@code toString()} does, so
 * the log lines and the cascade path of a call still name the machine of an entity whose identity cannot be printed,
 * and an {@link AmbiguousIdentityException} prints the identity it names the same way.
 */
class EntityRefTextTest {

    /** An identity that prints whatever its supplier answers or throws. */
    private record Id(Supplier<String> text) {
        @Override
        public String toString() {
            return text.get();
        }
    }

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> void sneaky(Throwable thrown) throws X {
        throw (X) thrown;
    }

    private static EntityRef refOf(Supplier<String> text) {
        return new EntityRef(DOCS, new Id(text), new Doc("1", DRAFT));
    }

    @Test
    void anIdWhoseToStringThrowsPrintsAMarkerAfterTheMachineKey() {
        EntityRef ref = refOf(() -> {
            throw new IllegalStateException("unprintable");
        });
        assertEquals("Doc/DocStatus#<toString threw java.lang.IllegalStateException>", ref.toString());
    }

    @Test
    void anIdWhoseToStringThrowsACheckedExceptionPrintsAMarkerToo() {
        EntityRef ref = refOf(() -> {
            EntityRefTextTest.<RuntimeException>sneaky(new IOException("unprintable"));
            return "never";
        });
        assertEquals("Doc/DocStatus#<toString threw java.io.IOException>", ref.toString());
    }

    @Test
    void anIdWhoseToStringAnswersNullPrintsNullAfterTheMachineKey() {
        assertEquals("Doc/DocStatus#null", refOf(() -> null).toString());
    }

    @Test
    void anErrorFromTheIdsToStringPropagates() {
        AssertionError fatal = new AssertionError("broken toString");
        EntityRef ref = refOf(() -> {
            throw fatal;
        });
        assertSame(fatal, assertThrows(AssertionError.class, ref::toString));
    }

    @Test
    void anAmbiguousIdentityThatCannotBePrintedStillReachesTheAbortAsItsCause() {
        Doc root = new Doc("root", DRAFT);
        Doc first = new Doc("first", REVIEW);
        Doc second = new Doc("second", REVIEW);
        Id shared = new Id(() -> {
            throw new IllegalStateException("unprintable");
        });
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.cascade(DOCS, first, PUBLISH);
                        ctx.cascade(DOCS, second, PUBLISH);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus)
                .identity((Doc doc) -> doc == first || doc == second ? shared : doc.id())).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            AmbiguousIdentityException ambiguous = assertInstanceOf(AmbiguousIdentityException.class,
                    thrown.getCause());
            assertEquals("two distinct objects with the identity Doc/DocStatus#<toString threw"
                    + " java.lang.IllegalStateException> within the same transaction", ambiguous.getMessage());
        }
    }
}
