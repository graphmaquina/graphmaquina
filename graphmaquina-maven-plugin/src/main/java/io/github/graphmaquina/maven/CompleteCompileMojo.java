package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * The {@code complete} goal: publishes the index of what javac accepted and seals the inventory of the main
 * compilation. It runs after the compiler goal bound to the same phase, so a compilation javac rejected never
 * reaches it and never leaves a verified index behind. It publishes nothing, and says so, when the build
 * compiles no main sources: in a project packaged as {@code pom}, when the default lifecycle runs no execution of
 * {@code compiler:compile}, or when {@code maven.main.skip} or {@code <skipMain>} switches the main compilation
 * off.
 */
public final class CompleteCompileMojo extends StateGraphMojo {

    @Override
    public void execute() throws MojoExecutionException {
        if (skips(CompilerExecution.MAIN)) {
            return;
        }
        complete(mainRequest());
    }
}
