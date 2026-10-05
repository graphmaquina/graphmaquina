package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionGuard;
import java.util.function.Function;

/** Test guard with a configurable verdict, which passes by default, and a count of its calls. */
public final class GuardProbe<E, S extends Enum<S>, T extends Enum<T>> implements TransitionGuard<E, S, T> {

    public Function<GuardContext<E, S, T>, GuardResult> verdict = (GuardContext<E, S, T> ctx) -> GuardResult.pass();
    public int calls;

    @Override
    public GuardResult test(GuardContext<E, S, T> ctx) {
        calls++;
        return verdict.apply(ctx);
    }
}
