/**
 * Source generators: they render the model as the source of each companion, in ASCII with every other character as a
 * Unicode escape, with what it writes at the head of its names, and leave writing it to the processor, which checks
 * those names against the companion's scope and writes the source through the Filer.
 */
package io.github.graphmaquina.processor.gen;
