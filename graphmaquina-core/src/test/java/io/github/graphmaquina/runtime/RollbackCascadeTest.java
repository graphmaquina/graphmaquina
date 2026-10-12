package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A retained action context cannot stage a cascade while the engine rolls a step back: an identity's hashCode, which
 * the rollback calls in the middle of its undo, would otherwise add a hop that the rollback then drops in place of the
 * one it undid, leaving a pending state that nothing commits, or that a later hop moves from and commits as a move the
 * graph does not have. The cascade is refused before it stages anything.
 */
class RollbackCascadeTest {

    private static final String REFUSAL = "cascades cannot run while the call rolls back a step";

    /** An identity whose hashCode runs {@link #onHash} once it is armed; it prints by name. */
    static final class ArmedId {
        private final String name;
        private Runnable onHash = () -> { };

        ArmedId(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ArmedId that && that.name.equals(name);
        }

        @Override
        public int hashCode() {
            onHash.run();
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph, Doc first,
                                            ArmedId firstId) {
        return MachineRegistry.builder().add(StateMachines.from(graph).state(Doc::status, Doc::setStatus)
                .identity((Doc doc) -> doc == first ? firstId : doc.id())).build();
    }

    @Test
    void aCascadeAttemptedWhileARouteRollsBackIsRefusedBeforeItStagesAnything() {
        Doc root = new Doc("root", DRAFT);
        Doc first = new Doc("first", REVIEW);
        Doc second = new Doc("second", REVIEW);
        ArmedId firstId = new ArmedId("first");
        List<String> refusals = new ArrayList<>();
        List<DocStatus> seen = new ArrayList<>();
        List<TransitionContext<Doc, DocStatus, DocTransition>> retained = new ArrayList<>();
        Runnable arm = () -> firstId.onHash = () -> {
            firstId.onHash = () -> { };
            try {
                retained.get(0).cascade(DOCS, second, PUBLISH);
            } catch (IllegalStateException refused) {
                refusals.add(refused.getMessage());
            }
        };
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        retained.add(ctx);
                        ctx.cascade(DOCS, first, PUBLISH);
                        arm.run();
                        return ActionOutcome.failed("route it");
                    })
                    .onFailure(DISCARD)
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED)
                    .action("Archive", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        seen.add(ctx.peer(DOCS).stateOf(second));
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = registry(graph, first, firstId)) {
            Outcome<Doc, DocStatus, DocTransition> outcome = registry.of(DOCS).execute(root, SUBMIT);
            assertEquals(OutcomeCode.FAILURE_ROUTED, outcome.code());
            assertEquals(1, outcome.records().size());
        }
        assertEquals(List.of(REFUSAL), refusals);
        assertEquals(List.of(REVIEW), seen);
        assertEquals(List.of(ARCHIVED, REVIEW, REVIEW), List.of(root.status(), first.status(), second.status()));
    }

    @Test
    void aRefusalThatEscapesTheIdentityInterruptsTheRollbackAndAbortsTheCall() {
        Doc root = new Doc("root", DRAFT);
        Doc first = new Doc("first", REVIEW);
        Doc second = new Doc("second", REVIEW);
        ArmedId firstId = new ArmedId("first");
        List<TransitionContext<Doc, DocStatus, DocTransition>> retained = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        retained.add(ctx);
                        ctx.cascade(DOCS, first, PUBLISH);
                        firstId.onHash = () -> {
                            firstId.onHash = () -> { };
                            retained.get(0).cascade(DOCS, second, PUBLISH);
                        };
                        return ActionOutcome.failed("route it");
                    })
                    .onFailure(DISCARD)
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = registry(graph, first, firstId)) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            IllegalStateException cause = assertInstanceOf(IllegalStateException.class, thrown.getCause());
            assertEquals(REFUSAL, cause.getMessage());
        }
        assertEquals(List.of(DRAFT, REVIEW, REVIEW), List.of(root.status(), first.status(), second.status()));
    }

    @Test
    void aRecoveryAfterANestedRollbackFindsNoStagedStateToMoveFrom() {
        Doc root = new Doc("root", DRAFT);
        Doc nested = new Doc("nested", DRAFT);
        Doc first = new Doc("first", REVIEW);
        Doc second = new Doc("second", DRAFT);
        ArmedId firstId = new ArmedId("first");
        List<String> refusals = new ArrayList<>();
        List<DocStatus> seen = new ArrayList<>();
        List<TransitionContext<Doc, DocStatus, DocTransition>> retained = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        if (ctx.entity() == root) {
                            ctx.cascade(DOCS, second, SUBMIT);
                            ctx.cascade(DOCS, nested, SUBMIT);
                        } else if (ctx.entity() == nested) {
                            retained.add(ctx);
                            ctx.cascade(DOCS, first, PUBLISH);
                            firstId.onHash = () -> {
                                firstId.onHash = () -> { };
                                try {
                                    retained.get(0).cascade(DOCS, second, PUBLISH);
                                } catch (IllegalStateException refused) {
                                    refusals.add(refused.getMessage());
                                }
                            };
                            return ActionOutcome.failed("route it");
                        }
                        return ActionOutcome.ok();
                    })
                    .onFailure(DISCARD)
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED)
                    .action("Archive", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        seen.add(ctx.peer(DOCS).stateOf(second));
                        ctx.cascade(DOCS, second, ARCHIVE);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = registry(graph, first, firstId)) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertEquals(Optional.of(REVIEW), thrown.from());
            assertEquals(Optional.of(ARCHIVE), thrown.transition());
        }
        assertEquals(List.of(REFUSAL), refusals);
        assertEquals(List.of(REVIEW), seen);
        assertEquals(List.of(DRAFT, DRAFT, REVIEW, DRAFT),
                List.of(root.status(), nested.status(), first.status(), second.status()));
    }

    @Test
    void aRollbackEndsEvenWhenAnIdentityTriesToCascadeEveryTimeItIsHashed() {
        Doc root = new Doc("root", DRAFT);
        Doc first = new Doc("first", REVIEW);
        ArmedId firstId = new ArmedId("first");
        List<String> refusals = new ArrayList<>();
        List<DocStatus> seen = new ArrayList<>();
        int[] hashes = {0};
        List<TransitionContext<Doc, DocStatus, DocTransition>> retained = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        retained.add(ctx);
                        ctx.cascade(DOCS, first, PUBLISH);
                        firstId.onHash = () -> {
                            if (++hashes[0] > 100) {
                                throw new AssertionError("the rollback keeps hashing");
                            }
                            try {
                                retained.get(0).cascade(DOCS, new Doc("fresh-" + hashes[0], REVIEW), PUBLISH);
                            } catch (IllegalStateException refused) {
                                refusals.add(refused.getMessage());
                            }
                        };
                        return ActionOutcome.failed("route it");
                    })
                    .onFailure(DISCARD)
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED)
                    .action("Archive", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        firstId.onHash = () -> { };
                        seen.add(ctx.from());
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = registry(graph, first, firstId)) {
            assertEquals(OutcomeCode.FAILURE_ROUTED, registry.of(DOCS).execute(root, SUBMIT).code());
        }
        assertEquals(List.of(DRAFT), seen);
        assertEquals(false, refusals.isEmpty());
        assertEquals(List.of(ARCHIVED, REVIEW), List.of(root.status(), first.status()));
    }
}
