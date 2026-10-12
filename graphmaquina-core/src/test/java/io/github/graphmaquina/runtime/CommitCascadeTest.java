package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A retained action context cannot enlarge the log after phase 2 has fixed the changes it will write. */
class CommitCascadeTest {

    @ParameterizedTest
    @CsvSource({"getter,single", "getter,all", "setter,single", "setter,all"})
    void aCommitCallbackCannotStageAnotherHop(String callback, String cascade) {
        Doc root = new Doc("root", DRAFT);
        Doc child = new Doc("child", DRAFT);
        FlowKey<String> childNote = FlowKey.of("child", String.class);
        AtomicReference<Optional<TransitionContext<Doc, DocStatus, DocTransition>>> retained =
                new AtomicReference<>(Optional.empty());
        List<IllegalStateException> refused = new ArrayList<>();
        Runnable attempt = () -> {
            try {
                TransitionContext<Doc, DocStatus, DocTransition> context = retained.get().orElseThrow();
                if (cascade.equals("single")) {
                    context.cascade(DOCS, child, SUBMIT);
                } else {
                    context.cascadeAll(DOCS, List.of(child), SUBMIT, CascadePolicy.COLLECT);
                }
            } catch (IllegalStateException failure) {
                refused.add(failure);
            }
        };
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "submit a draft").statePolicy(REVIEW, "publish a review")
                .statePolicy(PUBLISHED, "archive a publication")
                .transition(SUBMIT, DRAFT, REVIEW)
                .action("remember", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    if (ctx.entity() == root) {
                        retained.set(Optional.of(ctx));
                    } else {
                        ctx.flow().put(childNote, "the child ran");
                    }
                    return ActionOutcome.ok();
                }).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .transition(ARCHIVE, DRAFT, ARCHIVED).endTransition().build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state((Doc entity) -> {
                    if (entity == root && retained.get().isPresent() && callback.equals("getter")) {
                        attempt.run();
                    }
                    return entity.status();
                }, (Doc entity, DocStatus state) -> {
                    entity.setStatus(state);
                    if (entity == root && callback.equals("setter")) {
                        attempt.run();
                    }
                }).identity(Doc::id).recordTrail()).build()) {
            Outcome<Doc, DocStatus, DocTransition> outcome = registry.of(DOCS).execute(root, SUBMIT);
            assertInstanceOf(Outcome.Applied.class, outcome);
            assertEquals(1, refused.size());
            assertEquals(REVIEW, root.status());
            assertEquals(DRAFT, child.status());
            assertEquals(1, outcome.records().size());
            assertEquals(1, outcome.flow().trail().size());
            assertEquals(Optional.empty(), outcome.flow().get(childNote));
        }
    }
}
