package io.github.graphmaquina.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The sources one execution of the compiler hands javac: the source roots it compiles, the patterns it selects their
 * sources with, and whether they take in the vocabulary the generator writes and the carrier of the compilation. The
 * settings are read through the {@link CompilerConfiguration} of that execution, as it reads them.
 */
final class CompiledSources {

    /** The include that selects everything the generator emits outside the packages of a project. */
    private static final String GENERATED_INCLUDE = "io/github/graphmaquina/generated/**";

    private CompiledSources() {
    }

    /**
     * The patterns javac selects the sources of every root of this compilation with: {@code <includes>} and
     * {@code <excludes>} for the main compilation, {@code <testIncludes>} and {@code <testExcludes>} for the test
     * one, whose excludes maven-compiler-plugin 3.15.0 joins with its {@code <testIncrementalExcludes>} when it
     * selects them. The main compilation leaves its {@code <incrementalExcludes>} out of that selection, where it
     * joins its excludes with themselves instead, and reads them only in its check for stale sources, which
     * {@link #requireCarrierSelected} accounts for. None of these lists has a user property, so only the
     * configuration declares them.
     *
     * @param configuration the configuration of the execution of the compiler that runs this compilation
     * @return the patterns, which narrow nothing when the execution declares none of these lists
     */
    static SourcePatterns sourcePatterns(CompilerConfiguration configuration) {
        List<String> excludes = new ArrayList<>();
        for (String parameter : excludesParameters(configuration)) {
            excludes.addAll(patterns(configuration, parameter));
        }
        return new SourcePatterns(patterns(configuration, configuration.execution().includesParameter()), excludes,
            File.separatorChar);
    }

    /**
     * The source roots javac compiles when this execution declares {@code <compileSourceRoots>} of its own, which
     * replace the source roots of the project: each evaluated as {@link CompilerConfiguration#parameterValues}
     * evaluates it, then as an absolute path, a relative one resolved, as maven-compiler-plugin 3.15.0 resolves it,
     * against the working directory of the JVM running Maven. An empty value, or one that evaluates to nothing, is
     * left out.
     *
     * @param configuration the configuration of the execution of the compiler that runs this compilation
     * @return the roots; empty when the execution declares no such element, or one with neither text nor a child,
     *     for which Maven gives the compiler its default, the source roots of the project, so that javac compiles
     *     those; and also when the text of the element evaluates to nothing, as {@code ${project.compileSourceRoots}}
     *     does, which names the source roots of the project
     */
    static Optional<List<String>> compileSourceRoots(CompilerConfiguration configuration) {
        Optional<Xpp3Dom> element = configuration.element("compileSourceRoots");
        if (element.isEmpty() || element.get().getChildCount() == 0
                && (element.get().getValue() == null || element.get().getValue().isBlank())) {
            return Optional.empty();
        }
        return configuration.parameterValues("compileSourceRoots").map((List<String> declared) -> {
            List<String> roots = new ArrayList<>();
            for (String root : declared) {
                if (root != null) {
                    roots.add(Path.of(root).toAbsolutePath().toString());
                }
            }
            return List.copyOf(roots);
        });
    }

    /**
     * Whether javac compiles the sources under {@code directory} in the compiler execution of {@code configuration}:
     * it does unless that execution declares {@code <compileSourceRoots>} of its own that leave that directory out,
     * since otherwise it compiles the source roots of the project, the one this plugin adds included. A root is that
     * directory when both name it once their symbolic links are resolved, as {@link CompilerConfiguration#canonical}
     * compares them.
     */
    static boolean compilesDirectory(CompilerConfiguration configuration, Path directory) {
        Optional<List<String>> roots = compileSourceRoots(configuration);
        return roots.isEmpty() || roots.get().stream()
            .anyMatch((String root) -> CompilerConfiguration.canonical(Path.of(root))
                .equals(CompilerConfiguration.canonical(directory)));
    }

    /**
     * Refuses {@code <compileSourceRoots>} that leave out the directory the generator writes into. They replace the
     * source roots of the project for javac, the one this plugin adds included, so javac would compile the sources of
     * this compilation without the vocabulary they use, and the completion would find nothing of this compilation to
     * publish, after the preparation retired the outputs of the previous one. A root is that directory when both name
     * it once their symbolic links are resolved, as {@link CompilerConfiguration#canonical} compares them.
     *
     * @param configuration the configuration of the execution of the compiler that runs this compilation
     * @param request the request the calling goal generates for
     * @throws MojoExecutionException naming the directory the compiler leaves out
     */
    static void requireGeneratedSourcesCompiled(CompilerConfiguration configuration, StateGraphMojo.Request request)
            throws MojoExecutionException {
        Path generated = Path.of(request.sourcesOutput()).toAbsolutePath().normalize();
        if (!compilesDirectory(configuration, generated)) {
            throw new MojoExecutionException("the <compileSourceRoots> of the " + configuration.id() + " execution of"
                + " maven-compiler-plugin replace the source roots of the project and leave out " + generated
                + ", where the StateGraph generator writes the vocabulary of this compilation, so javac would compile"
                + " the sources of this compilation without it, and the completion would find nothing of this"
                + " compilation to publish, after the preparation retired the outputs of the previous compilation."
                + " Add <compileSourceRoot>" + generated + "</compileSourceRoot> to them, or leave compileSourceRoots"
                + " unset.");
        }
    }

    /**
     * The parameters whose patterns leave sources of this compilation out of what javac compiles: {@code excludes}
     * for the main compilation, and {@code testExcludes} and {@code testIncrementalExcludes} for the test one.
     */
    private static List<String> excludesParameters(CompilerConfiguration configuration) {
        CompilerExecution execution = configuration.execution();
        return execution == CompilerExecution.TEST
            ? List.of(execution.excludesParameter(), "testIncrementalExcludes")
            : List.of(execution.excludesParameter());
    }

    /**
     * Refuses includes or excludes that leave out the carrier of this compilation, the excludes being those
     * {@link #sourcePatterns} reads. maven-compiler-plugin selects the sources of every root with them, the root this
     * plugin generates into included, so patterns that miss the carrier would have javac compile the generated
     * vocabulary without it, and the completion would find nothing of this compilation to publish. Includes that
     * hold only empty values select no source at all, so javac would not run. The carrier is matched under the very
     * name the generator gives it: {@code GraphCompilation} and the first sixteen hexadecimal digits of the SHA-256
     * of the identity of the compilation, in {@code io/github/graphmaquina/generated/bootstrap}. It is selected as
     * {@link SourcePatterns#selects} selects a source on the platform the build runs on, so an include that matches it
     * is not enough when the scanner of the compiler never enters a directory above it, as for
     * {@code /io/github/graphmaquina/generated/**} where the file separator is {@code /}: its leading separator then
     * keeps the scanner out of every directory the include does not match itself. Where the separator is {@code \},
     * plexus-utils lets the scanner in for such an include as if it had no leading separator.
     *
     * <p>The main compilation also leaves the sources its {@code <incrementalExcludes>} match out of the check for
     * stale sources that decides whether it runs javac at all, while it still compiles them. The carrier, whose class
     * file the completion deletes, is what makes that check compile the module again after the preparation, so an
     * {@code <incrementalExcludes>} pattern that matches the carrier is refused as well: in a build in which nothing
     * else changed, javac would not run. The test compilation reads its {@code <testIncrementalExcludes>} in both
     * places, and they are among the excludes above.</p>
     *
     * @param configuration the configuration of the execution of the compiler that runs this compilation
     * @param request the request the calling goal generates for
     * @throws MojoExecutionException naming the pattern that leaves the carrier out, or the includes that hold only
     *     empty values, and the configuration that lets it in
     */
    static void requireCarrierSelected(CompilerConfiguration configuration, StateGraphMojo.Request request)
            throws MojoExecutionException {
        String carrier = CarrierTimestamp.CARRIER_PACKAGE + "/GraphCompilation" + carrierDigest(request.compilationId())
            + ".java";
        String compiler = " of the " + configuration.id() + " execution of maven-compiler-plugin";
        String consequence = ", the carrier the StateGraph generator emits for this compilation under "
            + request.sourcesOutput() + ": the compiler applies its patterns to that directory too, so javac would"
            + " compile the generated vocabulary without its carrier and the completion would find nothing to"
            + " publish.";
        String remedy = " Change that pattern so it matches nothing under io/github/graphmaquina/generated.";
        for (String parameter : excludesParameters(configuration)) {
            for (String exclude : patterns(configuration, parameter)) {
                if (SourcePatterns.matches(exclude, carrier, File.separatorChar)) {
                    throw new MojoExecutionException("the <" + parameter + "> pattern " + exclude + compiler
                        + " leaves out " + carrier + consequence + remedy);
                }
            }
        }
        if (configuration.execution() == CompilerExecution.MAIN) {
            for (String exclude : patterns(configuration, "incrementalExcludes")) {
                if (SourcePatterns.matches(exclude, carrier, File.separatorChar)) {
                    throw new MojoExecutionException("the <incrementalExcludes> pattern " + exclude + compiler
                        + " leaves " + carrier + ", the carrier the StateGraph generator emits for this compilation"
                        + " under " + request.sourcesOutput() + ", out of the check the compiler makes for stale"
                        + " sources before it runs javac. The carrier has no class file once a compilation completes,"
                        + " and that is what makes the compiler compile the module again after the preparation:"
                        + " without it, a build in which nothing else changed would not run javac, and the completion"
                        + " would find nothing of this compilation to publish." + remedy);
                }
            }
        }
        requireCarrierIncluded(configuration, carrier, compiler, consequence);
    }

    /**
     * Refuses includes that hold only empty values, or that select no {@code carrier} on the platform the build runs
     * on, explaining which directory above it the scanner of the compiler never enters when an include matches it.
     *
     * @param configuration the configuration of the execution of the compiler that runs this compilation
     * @param carrier the path of the carrier below its source root
     * @param compiler how a refusal names the execution of the compiler, after the parameter it quotes
     * @param consequence what a refusal says becomes of the carrier and of the completion
     */
    private static void requireCarrierIncluded(CompilerConfiguration configuration, String carrier, String compiler,
            String consequence) throws MojoExecutionException {
        CompilerExecution execution = configuration.execution();
        // maven-compiler-plugin includes every source when the includes are empty.
        if (configuration.parameterValues(execution.includesParameter()).orElse(List.of()).isEmpty()) {
            return;
        }
        List<String> includes = patterns(configuration, execution.includesParameter());
        if (includes.isEmpty()) {
            throw new MojoExecutionException("the <" + execution.includesParameter() + ">" + compiler + " hold only"
                + " empty values, which Maven turns into null: maven-compiler-plugin then adds no default include,"
                + " and the scanner of plexus-utils leaves the null out, so the compiler would select no source, "
                + carrier + " included, javac would not run and the completion would find nothing of this compilation"
                + " to publish. Remove those empty values, so that maven-compiler-plugin includes every source.");
        }
        SourcePatterns selection = sourcePatterns(configuration);
        if (selection.selects(carrier)) {
            return;
        }
        // An include can match the carrier while the scanner never reaches it, as one that starts with a separator
        // where the file separator is /. Where it is \, an include that matches the carrier lets the scanner into
        // every directory above it, so this explanation is never given there.
        String unreached = "";
        Optional<String> skipped = selection.skippedDirectory(carrier);
        Optional<String> matching = includes.stream()
            .filter((String include) -> SourcePatterns.matches(include, carrier, File.separatorChar)).findFirst();
        if (skipped.isPresent() && matching.isPresent()) {
            unreached = " The include " + matching.get() + " matches that path, but the scanner of the compiler never"
                + " enters " + skipped.get() + ": it enters a directory only when an include matches that directory and"
                + " no exclude does, or when an include that does not start with a separator could match a path below"
                + " it.";
        }
        throw new MojoExecutionException("the <" + execution.includesParameter() + ">" + compiler + ", " + includes
            + ", select no " + carrier + consequence + unreached + " Add <include>" + GENERATED_INCLUDE
            + "</include> to the <" + execution.includesParameter() + "> of that execution.");
    }

    /**
     * The digest that names the carrier of {@code compilationId}: the first sixteen hexadecimal digits of the
     * SHA-256 of its UTF-8 bytes. It copies the rule of the generator's
     * {@code io.github.graphmaquina.codegen.emit.CompilationMarkerEmitter}, whose classes this plugin does not
     * load, so the two must change together.
     */
    private static String carrierDigest(String compilationId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(compilationId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("this Java runtime provides no SHA-256, which every Java runtime must",
                absent);
        }
    }

    /** The patterns one list parameter of this execution declares, as Maven converts it, empty ones left out. */
    private static List<String> patterns(CompilerConfiguration configuration, String parameter) {
        List<String> patterns = new ArrayList<>();
        for (String pattern : configuration.parameterValues(parameter).orElse(List.of())) {
            if (pattern != null) {
                patterns.add(pattern);
            }
        }
        return List.copyOf(patterns);
    }
}
