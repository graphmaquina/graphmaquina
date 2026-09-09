package io.stategraph.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code prepare-test} goal: the counterpart of {@code prepare} for the test compilation.
 */
public final class PrepareTestCompileMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        prepare(CompilerExecution.TEST, testRequest());
    }
}
