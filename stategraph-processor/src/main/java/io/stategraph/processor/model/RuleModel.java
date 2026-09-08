package io.stategraph.processor.model;

import java.util.Optional;

/** One rule of a watch. {@code argument} is the peer state for ALL_IN/ANY_IN/NONE_IN and empty for CONDITION. */
public record RuleModel(RuleKind kind, String argument, Optional<Binding> condition, String goTo) { }
