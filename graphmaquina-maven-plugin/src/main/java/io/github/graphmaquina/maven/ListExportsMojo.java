package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code list-exports} goal: prints the StateGraph export index of every entry of the main compilation's compile
 * classpath that publishes one, with its location, the tool version that wrote it, its machines and its symbols, and
 * each reason {@code SG602} would refuse it.
 *
 * <p>Its descriptor binds it to no phase and forks the default lifecycle up to {@code compile} before it runs, so the
 * modules of a reactor that the project depends on have published their indexes when it reads them. It does nothing,
 * and says so, when the build compiles no main sources, as {@code generate} does.</p>
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
