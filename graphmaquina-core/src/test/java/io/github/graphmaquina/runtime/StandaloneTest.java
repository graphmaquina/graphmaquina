package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Facades;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A standalone machine is a registry of one: its facade, its watches and their validation work as they do in a
 * registry built with the same declarations.
 */
class StandaloneTest {

    @Test
    void aStandaloneMachineResolvesTheFacadeItDeclares() {
        Doc peer = new Doc("2", REVIEW);
        AtomicReference<DocStatus> seen = new AtomicReference<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascade(Facades.Docs.class, peer, PUBLISH);
                    seen.set(ctx.peer(Facades.Docs.class).stateOf(peer));
                    return ActionOutcome.ok();
                }).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> machine =
                StateMachines.from(graph).state(Doc::status, Doc::setStatus).as(Facades.Docs.class).build();
        Doc doc = new Doc("1", DRAFT);
        assertInstanceOf(Outcome.Applied.class, machine.execute(doc, SUBMIT));
        assertEquals(PUBLISHED, seen.get());
        assertEquals(REVIEW, doc.status());
        assertEquals(PUBLISHED, peer.status());
    }

    /** An observer in PUBLISHED watches the docs; a move of the watched one archives the observer after the commit. */
    @Test
    void aStandaloneMachineDispatchesItsOwnWatches() {
        Doc observer = new Doc("observer", PUBLISHED);
        Doc watched = new Doc("watched", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(doc),
                        (Doc moved) -> moved == watched ? Optional.of(observer) : Optional.<Doc>empty())
                    .when("always", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(ARCHIVE)
                    .endWatch()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> machine =
                StateMachines.from(graph).state(Doc::status, Doc::setStatus).build();
        Outcome.Applied<?, ?, ?> applied = assertInstanceOf(Outcome.Applied.class, machine.execute(watched, SUBMIT));
        assertEquals(1, applied.reactions().size());
        Reaction.Done done = assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
        assertInstanceOf(Outcome.Applied.class, done.outcome());
        assertEquals(REVIEW, watched.status());
        assertEquals(ARCHIVED, observer.status());
    }

    /** The registry refuses a watch on a machine it does not hold; a standalone machine holds only itself. */
    @Test
    void aStandaloneMachineRefusesAWatchOnAMachineItDoesNotHold() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(REVIEW, ITEMS, (Doc doc) -> List.<Item>of(), (Item item) -> Optional.<Doc>empty())
                    .when("packed", (Doc doc, Collection<? extends Item> items,
                            PeerView<Item, ItemStatus, ItemTransition> view) -> true)
                        .execute(PUBLISH)
                    .endWatch()
                .build();
        StateMachines.Builder<Doc, DocStatus, DocTransition> builder =
                StateMachines.from(graph).state(Doc::status, Doc::setStatus);
        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the watch of Doc/DocStatus.REVIEW points to Item/ItemStatus, which is not in the registry",
                error.getMessage());
    }
}
