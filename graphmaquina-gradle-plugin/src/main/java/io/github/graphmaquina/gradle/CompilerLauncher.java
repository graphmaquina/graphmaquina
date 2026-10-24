package io.github.graphmaquina.gradle;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.gradle.api.file.RegularFile;
import org.gradle.jvm.toolchain.JavaCompiler;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.jvm.toolchain.JavaLauncher;

/**
 * The launcher of the JDK one compilation compiles with: the {@code java} of the installation its
 * {@code javaCompiler} belongs to. That is a compiler a build gives the compile task, else the JDK of the
 * {@code forkOptions.javaHome} or {@code forkOptions.executable} Gradle starts javac from when {@code options.fork}
 * is set, else the project's Java toolchain. It is the convention of the launcher of a generation task, so the
 * generator parses the original sources on the JDK javac compiles them with, at a release that JDK supports.
 *
 * <p>Gradle selects the launcher of a toolchain by its specification and offers none for a given installation, so
 * this class names the {@code java} of that installation itself, as Gradle's own launchers do: the one in its
 * {@code bin} directory. It holds the compiler's metadata and that file as plain values, so the configuration
 * cache stores it as it stores a launcher Gradle selects.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class CompilerLauncher implements JavaLauncher {

    /** The installation the compiler belongs to, as Gradle describes it. */
    private final JavaInstallationMetadata metadata;

    /** The {@code java} of that installation. */
    private final RegularFile executable;

    /**
     * The launcher of the installation {@code compiler} belongs to.
     *
     * @param compiler the compiler of a compilation
     * @return the {@code java} of the same JDK
     */
    static CompilerLauncher of(JavaCompiler compiler) {
        JavaInstallationMetadata metadata = compiler.getMetadata();
        return new CompilerLauncher(metadata,
                metadata.getInstallationPath().file("bin/" + CodegenProcess.executableName("java")));
    }

    @Override
    public JavaInstallationMetadata getMetadata() {
        return metadata;
    }

    @Override
    public RegularFile getExecutablePath() {
        return executable;
    }
}
