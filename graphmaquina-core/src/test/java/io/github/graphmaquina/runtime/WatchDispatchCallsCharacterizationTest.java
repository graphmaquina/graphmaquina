package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.api.WatchCondition.allIn;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.StateGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * Pins every call a watch dispatch makes to user code that identifies entities: each identity function call and each
 * {@code equals}/{@code hashCode} of the keys they return, in order, for a watched move whose child has no owner, a
 * watched move whose owner does not complete yet, and the move that completes it.
 */
class WatchDispatchCallsCharacterizationTest {

    /** States shared by both machines. */
    enum Two { A, B }

    /** The only identifier of a child. */
    enum Go { GO }

    /** The only identifier of the owner. */
    enum Done { COMPLETE }

    /** An identity key whose {@code equals} and {@code hashCode} write each call to the shared log. */
    @RequiredArgsConstructor
    static final class Key {
        private final String id;
        private final StringBuilder log;

        @Override
        public boolean equals(Object other) {
            log.append("e(").append(id).append(',').append(other).append(')');
            return other instanceof Key key && key.id.equals(id);
        }

        @Override
        public int hashCode() {
            log.append("h(").append(id).append(')');
            return id.hashCode();
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** The watched entity: a state, a key and its owner, if any. */
    static final class Child {
        private Two state = Two.A;
        private final Key key;
        private Optional<Owner> owner = Optional.empty();

        Child(String id, StringBuilder log) {
            this.key = new Key(id, log);
        }
    }

    /** The watching entity: a state, a key and its children. */
    static final class Owner {
        private Two state = Two.A;
        private final Key key;
        private final List<Child> children = new ArrayList<>();

        Owner(String id, StringBuilder log) {
            this.key = new Key(id, log);
        }
    }

    private static final MachineKey<Child, Two, Go> CHILD = MachineKey.of(Child.class, Two.class, Go.class);
    private static final MachineKey<Owner, Two, Done> OWNER = MachineKey.of(Owner.class, Two.class, Done.class);

    @Test
    void everyIdentityCallOfAWatchDispatchKeepsItsOrder() {
        StringBuilder log = new StringBuilder();
        Owner owner = new Owner("owner", log);
        Child first = child("first", owner, log);
        Child second = child("second", owner, log);
        MachineRegistry registry = registry(log);
        Child orphan = new Child("orphan", log);
        log.setLength(0);
        registry.of(CHILD).execute(orphan, Go.GO);
        String ownerless = drain(log);
        registry.of(CHILD).execute(first, Go.GO);
        String watched = drain(log);
        registry.of(CHILD).execute(second, Go.GO);
        String completing = drain(log);
        assertEquals("id(orphan)h(orphan)h(orphan)", ownerless);
        assertEquals("id(first)h(first)h(first)id(owner)h(owner)id(first)id(second)", watched);
        assertEquals("id(second)h(second)h(second)id(owner)h(owner)id(first)id(second)id(owner)h(owner)h(owner)",
                completing);
        assertEquals(Two.B, owner.state);
    }

    private static Child child(String id, Owner owner, StringBuilder log) {
        Child child = new Child(id, log);
        child.owner = Optional.of(owner);
        owner.children.add(child);
        return child;
    }

    private static MachineRegistry registry(StringBuilder log) {
        GraphDefinition<Child, Two, Go> children = StateGraph.define(CHILD).initial(Two.A).terminal(Two.B)
                .statePolicy(Two.A, "a child only goes").transition(Go.GO, Two.A, Two.B).endTransition().build();
        GraphDefinition<Owner, Two, Done> owners = StateGraph.define(OWNER).initial(Two.A).terminal(Two.B)
                .statePolicy(Two.A, "an owner only completes").transition(Done.COMPLETE, Two.A, Two.B)
                .endTransition().watch(Two.A, CHILD, (Owner node) -> node.children, (Child child) -> child.owner)
                .when(allIn(Two.B)).execute(Done.COMPLETE).endWatch().build();
        return MachineRegistry.builder()
                .add(StateMachines.from(children).state((Child node) -> node.state,
                        (Child node, Two to) -> node.state = to).identity((Child node) -> identity(node.key, log)))
                .add(StateMachines.from(owners).state((Owner node) -> node.state,
                        (Owner node, Two to) -> node.state = to).identity((Owner node) -> identity(node.key, log)))
                .build();
    }

    private static Key identity(Key key, StringBuilder log) {
        log.append("id(").append(key).append(')');
        return key;
    }

    private static String drain(StringBuilder log) {
        String drained = log.toString();
        log.setLength(0);
        return drained;
    }
}
