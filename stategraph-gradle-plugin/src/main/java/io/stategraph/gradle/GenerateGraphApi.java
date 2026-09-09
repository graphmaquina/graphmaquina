package io.stategraph.gradle;

import org.gradle.api.DefaultTask;

/**
 * Task registered once per Java source set to generate its StateGraph API sources. This is a placeholder in the
 * plugin bootstrap: task 11.2 wires it to invoke {@code stategraph-codegen}.
 */
public class GenerateGraphApi extends DefaultTask {
}
