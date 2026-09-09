package io.stategraph.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code complete-test} goal: the counterpart of {@code complete} for the test compilation.
 */
public final class CompleteTestCompileMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        complete(testRequest());
    }
}
