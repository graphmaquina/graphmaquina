/**
 * The typed graph DSL. {@link io.github.graphmaquina.dsl.StateGraph#define StateGraph.define(key)} opens the builder
 * that declares one machine, and its {@code build()} validates the graph and returns its
 * {@link io.github.graphmaquina.api.GraphDefinition}. The package is public and an application may call it
 * directly. The code the annotation processor generates calls it too, and the processor checks the machines it
 * reads with the same {@link io.github.graphmaquina.dsl.MachineOutlineValidator}.
 */
package io.github.graphmaquina.dsl;
