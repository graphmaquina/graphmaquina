package io.stategraph.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code generate-test} goal: the counterpart of {@code generate} for the test compilation, whose generated
 * directory becomes a test compile source root.
 */
public final class GenerateTestMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        Request request = testRequest();
        run("generate", request);
        project().addTestCompileSourceRoot(request.sourcesOutput());
    }
}
