package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code prepare-test} goal: the counterpart of {@code prepare} for the test compilation.
 */
public final class PrepareTestCompileMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        if (skips(CompilerExecution.TEST)) {
            return;
        }
        prepare(testRequest());
    }
}
