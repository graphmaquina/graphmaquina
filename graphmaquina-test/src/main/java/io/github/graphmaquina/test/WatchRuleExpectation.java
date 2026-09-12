package io.github.graphmaquina.test;

import java.util.Objects;

/**
 * Expected shape of one rule of a watch: the public name of its condition and the state the rule moves
 * to when the condition holds. A list of these is what {@code WatchAssert.hasRulesExactly} compares
 * against, so declaration order and repeated rules are both significant.
 *
 * @param <S> state enum of the watching machine
 * @param condition public name of the rule's condition
 * @param target state the rule moves to
 */
public record WatchRuleExpectation<S extends Enum<S>>(String condition, S target) {

    public WatchRuleExpectation {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(target, "target");
    }
}
