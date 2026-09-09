package io.stategraph.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code complete} goal: publishes the index of what javac accepted and seals the inventory of the main
 * compilation. It runs after the compiler goal bound to the same phase, so a compilation javac rejected never
 * reaches it and never leaves a verified index behind.
 */
public final class CompleteCompileMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        run("complete-compile", mainRequest());
    }
}
