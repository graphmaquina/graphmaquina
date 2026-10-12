package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code prepare} goal: retires everything the previous cycle of the main compilation owned and invalidates
 * its published index, so a renamed or deleted declaration cannot keep publishing binaries javac no longer
 * writes. Like the {@code generate} goal of the same compilation, it builds the request from the project model
 * and writes it before the generator runs.
 *
 * <p>It then dates the carrier of this generation past the newest file the class output held before the
 * retirement, a safeguard for a compiler that decides by timestamps alone: maven-compiler-plugin 3.15.0 recompiles
 * the module without it, since the preparation leaves the carrier without a class file. It retires nothing at
 * all, and says so, when the build compiles no main sources: in a project packaged as {@code pom}, when the
 * default lifecycle runs no execution of {@code compiler:compile}, or when {@code maven.main.skip} or
 * {@code <skipMain>} switches the main compilation off.</p>
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
