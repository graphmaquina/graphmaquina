package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Test action with configurable behaviour and a record of every call, identity included. */
public final class Probe<E, S extends Enum<S>, T extends Enum<T>> implements TransitionAction<E, S, T> {

    public Function<TransitionContext<E, S, T>, ActionOutcome> behaviour =
            (TransitionContext<E, S, T> ctx) -> ActionOutcome.ok();
    public final List<String> calls = new ArrayList<>();

    @Override
    public ActionOutcome process(TransitionContext<E, S, T> ctx) {
        calls.add(ctx.entity() + ":" + ctx.from() + "-" + ctx.transition() + ">" + ctx.to() + "@" + ctx.depth());
        return behaviour.apply(ctx);
    }
}
