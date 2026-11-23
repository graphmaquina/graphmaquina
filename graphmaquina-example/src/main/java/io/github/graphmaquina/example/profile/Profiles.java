package io.github.graphmaquina.example.profile;

import static io.github.graphmaquina.example.profile.ProfileStatus.COMPLETE;
import static io.github.graphmaquina.example.profile.ProfileStatus.DRAFT;
import static io.github.graphmaquina.example.profile.ProfileStatus.NAMED;
import static io.github.graphmaquina.example.profile.ProfileTransition.EDIT;
import static io.github.graphmaquina.example.profile.ProfileTransition.NAME;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.AutomaticChain;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

/**
 * The profile machine: a draft takes its name and then its address, and the machine takes the two automatic edges that
 * do it on its own only when the application calls {@code advance}. Editing a profile commits a self-loop and chains
 * nothing, so a profile is never completed behind the back of the call that saved it. The processor generates
 * ProfilesRuntime.
 */
@GenerateGraphApi
@ProfilesGraph.Definition(initial = DRAFT, terminals = COMPLETE, allowsCycle = {DRAFT, NAMED},
        automaticChain = AutomaticChain.ON_ADVANCE, state = ProfileRefs.Status.class, id = ProfileRefs.Id.class)
@ProfilesGraph.StatePolicy(state = DRAFT, otherwise = Profiles.DRAFT_POLICY)
@ProfilesGraph.StatePolicy(state = NAMED, otherwise = Profiles.NAMED_POLICY)
@ProfilesGraph.Transition(id = NAME, from = DRAFT, to = NAMED, automatic = true,
        guards = ProfileGuardsRefs.HasName.class)
@ProfilesGraph.Transition(id = ProfileTransition.COMPLETE, from = NAMED, to = COMPLETE, automatic = true,
        guards = ProfileGuardsRefs.HasAddress.class)
public interface Profiles extends Machine<Profile, ProfileStatus, ProfileTransition> {

    /** Saves what the user typed: a self-loop of a draft and of a named profile, which takes no automatic edge. */
    @ProfilesGraph.Transition(id = EDIT, from = DRAFT, to = DRAFT)
    @ProfilesGraph.Transition(id = EDIT, from = NAMED, to = NAMED)
    Outcome<Profile, ProfileStatus, ProfileTransition> edit(Profile profile);

    String DRAFT_POLICY = "a draft is edited until it has a name";
    String NAMED_POLICY = "a named profile is edited until it has an address";
}
