package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code complete-test} goal: the counterpart of {@code complete} for the test compilation.
 */
public final class CompleteTestCompileMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        if (skips(CompilerExecution.TEST)) {
            return;
        }
        complete(testRequest());
    }
}
