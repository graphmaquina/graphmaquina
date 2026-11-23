package io.github.graphmaquina.example.profile;

import io.github.graphmaquina.annotation.GraphGuard;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;

/**
 * The guards of the profile's automatic edges: each refuses with the reason the caller reads. Static methods, so the
 * machine needs no instance of this class.
 */
public final class ProfileGuards {

    private ProfileGuards() { }

    @GraphGuard
    public static GuardResult hasName(GuardContext<Profile, ProfileStatus, ProfileTransition> ctx) {
        return ctx.entity().name().isBlank() ? GuardResult.reject("the profile has no name") : GuardResult.pass();
    }

    @GraphGuard
    public static GuardResult hasAddress(GuardContext<Profile, ProfileStatus, ProfileTransition> ctx) {
        return ctx.entity().address().isBlank()
                ? GuardResult.reject("the profile has no address")
                : GuardResult.pass();
    }
}
