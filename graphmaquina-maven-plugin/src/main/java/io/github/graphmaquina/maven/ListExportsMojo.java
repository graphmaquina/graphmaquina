package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code list-exports} goal: prints the StateGraph export index of every entry of the main compilation's compile
 * classpath that publishes one, with its location, the tool version that wrote it, its machines and its symbols, and
 * each reason {@code SG602} would refuse it.
 *
 * <p>Its descriptor binds it to no phase and forks no lifecycle: it reads the dependencies Maven resolves and runs
 * neither the generation nor the compilation, so it lists an index that the generation would refuse. A module of the
 * same reactor is read once it compiled earlier in the same run. It does nothing, and says so, when the build compiles
 * no main sources, as {@code generate} does.</p>
 */
public final class ListExportsMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        if (skips(CompilerExecution.MAIN)) {
            return;
        }
        listExports();
    }
}
