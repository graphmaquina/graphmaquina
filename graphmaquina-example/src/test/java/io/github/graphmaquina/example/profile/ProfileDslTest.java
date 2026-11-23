package io.github.graphmaquina.example.profile;

import static io.github.graphmaquina.example.profile.ProfileStatus.COMPLETE;
import static io.github.graphmaquina.example.profile.ProfileStatus.DRAFT;
import static io.github.graphmaquina.example.profile.ProfileStatus.NAMED;
import static io.github.graphmaquina.example.profile.ProfileTransition.EDIT;
import static io.github.graphmaquina.example.profile.ProfileTransition.NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.AutomaticChain;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.GraphSnapshots;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The profile's graph written with the DSL in place of the annotations of {@link Profiles}: the structural snapshot of
 * the annotated graph, which records its ON_ADVANCE chain, pins it, and a machine built from it chains only on advance.
 */
class ProfileDslTest {

    static GraphDefinition<Profile, ProfileStatus, ProfileTransition> graph() {
        return StateGraph.define(MachineKey.of(Profile.class, ProfileStatus.class, ProfileTransition.class))
                .automaticChain(AutomaticChain.ON_ADVANCE)
                .initial(DRAFT).terminal(COMPLETE).allowsCycle(DRAFT).allowsCycle(NAMED)
                .statePolicy(DRAFT, Profiles.DRAFT_POLICY)
                .statePolicy(NAMED, Profiles.NAMED_POLICY)
                .transition(NAME, DRAFT, NAMED).automatic().guard("hasName", ProfileGuards::hasName).endTransition()
                .transition(ProfileTransition.COMPLETE, NAMED, COMPLETE).automatic()
                    .guard("hasAddress", ProfileGuards::hasAddress)
                    .endTransition()
                .transition(EDIT, DRAFT, DRAFT).endTransition()
                .transition(EDIT, NAMED, NAMED).endTransition()
                .build();
    }

    @Test
    void theDslDeclaresTheAnnotatedGraph() {
        GraphSnapshots.assertMatches(graph(), "contracts/profile.graph");
    }

    @Test
    void aMachineOfTheDslGraphChainsOnlyOnAdvance() {
        StateMachine<Profile, ProfileStatus, ProfileTransition> profiles = StateMachines.from(graph())
                .state(Profile::status, Profile::setStatus).identity(Profile::id).build();
        Profile profile = new Profile("d1");
        profile.setName("Ada");
        profile.setAddress("1 Main Street");

        assertEquals(List.of(), profiles.execute(profile, EDIT).automaticSteps());
        assertEquals(DRAFT, profile.status());
        profiles.advance(profile);

        assertEquals(COMPLETE, profile.status());
    }
}
