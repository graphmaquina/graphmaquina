package io.github.graphmaquina.test;

import java.util.Objects;

/**
 * Expected shape of one rule of a watch: the public name of its condition and the identifier of the
 * owner's transition the rule executes when the condition holds. A list of these is what
 * {@code WatchAssert.hasRulesExactly} compares against, so declaration order and repeated rules are both
 * significant.
 *
 * @param <T> transition identifier enum of the watching machine
 * @param condition public name of the rule's condition
 * @param transition identifier of the transition the rule executes
 */
public record WatchRuleExpectation<T extends Enum<T>>(String condition, T transition) {

    public WatchRuleExpectation {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(transition, "transition");
    }
}
