/**
 * SPI: machine and registry construction, used by the code the annotation processor generates: a generated
 * {@code <Set>Runtime} builds a {@link MachineRegistry} from {@link StateMachines} builders and hands out the
 * facades. A graph built with the DSL runs through this package directly, standalone or in a registry.
 */
package io.github.graphmaquina.runtime;
