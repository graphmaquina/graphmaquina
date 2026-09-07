package io.stategraph.fixtures;

import io.stategraph.api.GuardContext;
import io.stategraph.api.GuardResult;
import io.stategraph.api.TransitionGuard;
import java.util.function.Function;

public final class GuardProbe<E, S extends Enum<S>> implements TransitionGuard<E, S> {

    public Function<GuardContext<E, S>, GuardResult> verdict = (GuardContext<E, S> ctx) -> GuardResult.pass();
    public int calls;

    @Override
    public GuardResult test(GuardContext<E, S> ctx) {
        calls++;
        return verdict.apply(ctx);
    }
}
