package io.stategraph.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code prepare} goal: retires everything the previous cycle of the main compilation owned and invalidates
 * its published index, so a renamed or deleted declaration cannot keep publishing binaries javac no longer
 * writes. It runs over the request the {@code generate} goal of the same compilation wrote.
 */
public final class PrepareCompileMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        run("prepare-compile", mainRequest());
    }
}
