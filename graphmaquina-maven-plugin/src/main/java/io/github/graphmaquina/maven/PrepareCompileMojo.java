package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code prepare} goal: retires everything the previous cycle of the main compilation owned and invalidates
 * its published index, so a renamed or deleted declaration cannot keep publishing binaries javac no longer
 * writes. Like the {@code generate} goal of the same compilation, it builds the request from the project model
 * and writes it before the generator runs.
 *
 * <p>It then dates the carrier of this generation past the newest file the class output held before the
 * retirement, so the incremental compiler recompiles the module it has just been left without. It retires
 * nothing at all, and says so, when the build compiles no main sources: in a project packaged as {@code pom}, or
 * when {@code maven.main.skip} or {@code <skipMain>} switches the main compilation off.</p>
 */
public final class PrepareCompileMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        if (skips(CompilerExecution.MAIN)) {
            return;
        }
        prepare(mainRequest());
    }
}
