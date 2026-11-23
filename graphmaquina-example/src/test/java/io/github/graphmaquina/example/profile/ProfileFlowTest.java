package io.github.graphmaquina.example.profile;

import static io.github.graphmaquina.example.profile.ProfileStatus.COMPLETE;
import static io.github.graphmaquina.example.profile.ProfileStatus.DRAFT;
import static io.github.graphmaquina.example.profile.ProfileTransition.EDIT;
import static io.github.graphmaquina.example.profile.ProfileTransition.NAME;
import static io.github.graphmaquina.test.GraphAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.AutomaticChain;
import io.github.graphmaquina.api.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The profile's advance-only chain, asserted through the public testing API. Saving a profile commits its edit and
 * takes no automatic edge, however complete the data is; {@code advance} takes the name and then the address in one
 * call.
 */
class ProfileFlowTest {

    final ProfileMachines machines = ProfileMachinesRuntime.builder().build();
    final Profiles profiles = machines.profiles();

    @AfterEach
    void close() {
        machines.close();
    }

    @Test
    void theGraphTakesItsAutomaticEdgesOnlyOnAdvance() {
        assertThat(profiles.definition()).hasAutomaticChain(AutomaticChain.ON_ADVANCE);
    }

    @Test
    void anEditCommitsWithoutChaining() {
        Profile profile = new Profile("p1");
        profile.setName("Ada");
        profile.setAddress("1 Main Street");

        Outcome<Profile, ProfileStatus, ProfileTransition> outcome = profiles.edit(profile);

        assertThat(outcome).isApplied().requestedTransition(EDIT).movedTo(DRAFT).hasEntityState(Profile::status, DRAFT);
        assertTrue(outcome.automaticSteps().isEmpty());
        assertEquals(DRAFT, profile.status());
    }

    @Test
    void advanceCompletesTheProfileInOneCall() {
        Profile profile = new Profile("p2");
        profile.setName("Ada");
        profile.setAddress("1 Main Street");
        profiles.edit(profile);

        Outcome<Profile, ProfileStatus, ProfileTransition> outcome = profiles.advance(profile);

        assertThat(outcome).isApplied().requestedTransition(NAME).hasEntityState(Profile::status, COMPLETE)
                .automaticSteps().forEntity(ProfilesRuntime.KEY, profile).single().isDone().outcome().isApplied()
                .requestedTransition(ProfileTransition.COMPLETE).movedTo(COMPLETE);
    }
}
