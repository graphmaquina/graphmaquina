package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Test action with configurable behaviour and a record of every call. */
public final class Probe<E, S extends Enum<S>> implements TransitionAction<E, S> {

    public Function<TransitionContext<E, S>, ActionOutcome> behaviour = (TransitionContext<E, S> ctx) -> ActionOutcome.ok();
    public final List<String> calls = new ArrayList<>();

    @Override
    public ActionOutcome process(TransitionContext<E, S> ctx) {
        calls.add(ctx.entity() + ":" + ctx.from() + ">" + ctx.to() + "@" + ctx.depth());
        return behaviour.apply(ctx);
    }
}
