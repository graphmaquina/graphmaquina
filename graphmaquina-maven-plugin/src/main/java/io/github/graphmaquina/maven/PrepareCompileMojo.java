package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code prepare} goal: retires everything the previous cycle of the main compilation owned and invalidates
 * its published index, so a renamed or deleted declaration cannot keep publishing binaries javac no longer
 * writes. It runs over the request the {@code generate} goal of the same compilation wrote.
 *
 * <p>It then dates the carrier of this generation past the newest output the retirement removed, so the
 * incremental compiler recompiles the module it has just been left without, and it refuses to retire anything
 * at all when the build switched the main compilation off.</p>
 */
public final class PrepareCompileMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        prepare(CompilerExecution.MAIN, mainRequest());
    }
}
