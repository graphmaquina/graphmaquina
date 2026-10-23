package io.github.graphmaquina.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import javax.lang.model.SourceVersion;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Whether javac runs the StateGraph annotation processor for one compilation: the {@code -proc:} option and the
 * {@code -processor} list it takes, and where it finds the processor, on the processor path it is given or, without
 * one, among the dependencies of the project. The settings are read through the {@link CompilerConfiguration} of the
 * execution of the compiler, as it reads them.
 */
final class ProcessorPathRequirements {

    /** The coordinates of the annotation processor that publishes the index of an accepted compilation. */
    private static final String PROCESSOR_GROUP = "io.github.graphmaquina";
    private static final String PROCESSOR_ARTIFACT = "graphmaquina-processor";

    /** That annotation processor, as a {@code -processor} list names it. */
    private static final String PROCESSOR_CLASS = "io.github.graphmaquina.processor.StateGraphProcessor";

    /** The javac option whose list names the only annotation processors javac runs. */
    private static final String PROCESSOR_LIST = "-processor";

    /**
     * The first Java release whose javac runs an annotation processor it finds on the class path only when annotation
     * processing is requested.
     */
    private static final int REQUESTED_PROCESSING_RELEASE = 23;

    /**
     * The two names of the javac option that gives javac a processor path of its own. javac takes the last path either
     * name gives it, and also takes the second as {@code --processor-path=path}.
     */
    private static final List<String> PROCESSOR_PATH_OPTIONS = List.of("-processorpath", "--processor-path");

    /** The javac option that gives javac a module path to find annotation processors on, which it then takes alone. */
    private static final String PROCESSOR_MODULE_PATH = "--processor-module-path";

    /** The class file of the StateGraph annotation processor, as a processor path holds it. */
    private static final String PROCESSOR_FILE = PROCESSOR_CLASS.replace('.', '/') + ".class";

    private ProcessorPathRequirements() {
    }

    /**
     * Refuses a compilation that switches annotation processing off, that has javac run a list of annotation
     * processors without the StateGraph one or with a name that is not a valid qualified class name, which javac
     * cannot load, that gives javac a processor module path, whose processor path does not carry the StateGraph
     * annotation processor, or which, without a processor path, does not carry it among the dependencies of the
     * project, or carries it there for a javac that would not run it. A well-formed name of a class that is not on
     * the processor path, such as a misspelt one, is not refused: javac fails on it after the preparation, like any
     * failed compilation, and the next preparation reads the pending inventory that compilation left to clean up after
     * it.
     *
     * <p>The processor is the only thing that publishes the index of what javac accepted. A compilation that
     * runs without it publishes nothing, and since the preparation has already retired the index of the previous
     * cycle by then, the build would be left with no index at all and would fail only after that retirement, in
     * javac or in the {@code complete} goal.</p>
     *
     * <p>javac takes the last {@code -proc:} option it is given, and maven-compiler-plugin passes {@code <proc>}
     * ahead of the compiler arguments, so a {@code -proc:} compiler argument wins over it. Given a {@code -processor}
     * list, javac runs only the processors it names, and of several such lists it takes the last one;
     * maven-compiler-plugin passes {@code <annotationProcessors>} as such a list ahead of the compiler arguments, even
     * when it names nothing, so a {@code -processor} compiler argument wins over it as well.</p>
     *
     * <p>Given a processor path, javac looks for annotation processors there and not on the class path. The processor
     * path it takes is the last one a {@code -processorpath} or {@code --processor-path} compiler argument gives it,
     * else the one maven-compiler-plugin passes ahead of the compiler arguments when {@code <annotationProcessorPaths>}
     * holds a path; one that holds none counts as absent, as it does for the compiler. A processor path given as a
     * compiler argument, evaluated as Maven evaluates it for the compiler, must reach the class of the StateGraph
     * processor, looked up as the processor class loader of javac looks it up: through each entry of the path,
     * resolved where javac runs, what the {@code Class-Path} of a jar's manifest names, and the versioned entries a
     * multi-release jar holds for the release of the JDK that javac belongs to, as its {@code release} file declares
     * it, or for any release when that file declares none. A path that still holds an expression after that
     * evaluation, such as one about the running goal, or a property not set yet when {@code generate} runs, which a
     * plugin bound to a later phase may still set, is not checked; a compiler argument that is one expression naming
     * an object Maven does not take for a {@code String}, such as {@code ${project.file}}, is left out, as Maven leaves
     * it out. Otherwise {@code <annotationProcessorPaths>} must name the processor. A processor module path is
     * refused, which StateGraph does not support: javac rejects one next to a processor path, which
     * maven-compiler-plugin passes for every {@code <annotationProcessorPaths>} that holds a path, and without a
     * processor path it looks for the processors on that module path instead of the class path.</p>
     *
     * <p>Without a processor path, javac finds the processors on the class path, among the dependencies of the
     * project, which must then carry the processor. From Java 23 on, javac runs the processors it finds there only
     * when annotation processing is requested: by {@code -proc:full} or {@code -proc:only} as the last {@code -proc:}
     * option it takes, or by a {@code -processor} list. A processor that is only a dependency of the project is
     * therefore refused when {@code javac} belongs to a JDK whose release file declares Java 23 or later and nothing
     * requests processing. A JDK whose release file declares no release is taken for an older one, whose javac runs
     * such a processor anyway.</p>
     *
     * @param configuration the configuration of the execution of the compiler that runs this compilation
     * @param project the project being built, whose resolved artifacts carry the processor when javac is given no
     *     processor path
     * @param javac the javac this compilation runs, as {@link CompilerConfiguration#requireAgreementWith} resolves it
     * @throws MojoExecutionException naming what switched the processor off, the list that leaves it out or holds a
     *     name that is not a valid qualified class name, where the processor is missing, or the settings that would
     *     make javac run a processor it finds on the class path
     */
    static void requireAnnotationProcessor(CompilerConfiguration configuration, MavenProject project,
            CompilerConfiguration.Javac javac) throws MojoExecutionException {
        Optional<CompilerConfiguration.Setting> proc = configuration.processing();
        if (proc.isPresent() && "none".equalsIgnoreCase(proc.get().value())) {
            throw noProcessing(configuration, proc.get().toString());
        }
        boolean listed = requireProcessorListed(configuration);
        if (requireProcessorPath(configuration, javac)) {
            return;
        }
        boolean requested = listed || proc.filter((CompilerConfiguration.Setting option) ->
            "full".equalsIgnoreCase(option.value()) || "only".equalsIgnoreCase(option.value())).isPresent();
        requireProcessorOnClassPath(configuration, project, javac, requested);
    }

    /**
     * Refuses a processor module path, and a processor path that does not carry the StateGraph annotation processor:
     * the last one a compiler argument gives javac, unless it still holds an expression, else the one
     * {@code <annotationProcessorPaths>} gives it when they hold a path.
     *
     * @param configuration the configuration of the execution of the compiler that runs this compilation
     * @param javac the javac this compilation runs, which resolves the entries of a processor path
     * @return whether javac is given a processor path, the only place it then looks for annotation processors
     */
    private static boolean requireProcessorPath(CompilerConfiguration configuration,
            CompilerConfiguration.Javac javac) throws MojoExecutionException {
        Optional<CompilerConfiguration.Setting> modulePath = configuration.lastOption(List.of(PROCESSOR_MODULE_PATH));
        if (modulePath.isPresent()) {
            throw new MojoExecutionException(modulePath.get() + " of the " + configuration.id() + " execution of"
                + " maven-compiler-plugin makes javac look for annotation processors on a module path, which"
                + " StateGraph does not support: it reads the processor path javac takes. Remove that argument, and"
                + " declare " + PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " in <annotationProcessorPaths> of that"
                + " execution, after Lombok when the project uses Lombok.");
        }
        Optional<CompilerConfiguration.Setting> pathArgument = configuration.lastOption(PROCESSOR_PATH_OPTIONS);
        if (pathArgument.isPresent()) {
            if (pathArgument.get().value().contains("${")
                    || holdsProcessor(configuration, pathArgument.get().value(), javac)) {
                return true;
            }
            throw new MojoExecutionException(pathArgument.get() + " of the " + configuration.id() + " execution of"
                + " maven-compiler-plugin gives javac the processor path it takes, the only place it then looks for"
                + " annotation processors, and that path reaches no " + PROCESSOR_FILE + ", so javac would compile the"
                + " generated vocabulary and publish no index of what it accepted, and the preparation that follows"
                + " would retire the index of the previous compilation. Add the jar of " + PROCESSOR_GROUP + ':'
                + PROCESSOR_ARTIFACT + ", and the jars it depends on, to that path, or remove that argument and declare"
                + " the processor in <annotationProcessorPaths> of that execution, after Lombok when the project uses"
                + " Lombok.");
        }
        Optional<Xpp3Dom> paths = configuration.element("annotationProcessorPaths")
            .filter((Xpp3Dom declared) -> declared.getChildCount() > 0);
        if (paths.isPresent()) {
            for (Xpp3Dom path : paths.get().getChildren()) {
                if (PROCESSOR_GROUP.equals(configuration.childValue(path, "groupId"))
                        && PROCESSOR_ARTIFACT.equals(configuration.childValue(path, "artifactId"))) {
                    return true;
                }
            }
            throw missingProcessor("the <annotationProcessorPaths> of the " + configuration.id()
                + " execution of maven-compiler-plugin");
        }
        return false;
    }

    /**
     * Refuses a compilation without a processor path when the dependencies of the project do not carry the StateGraph
     * annotation processor, or when they carry it but javac runs a processor it finds on the class path only when
     * annotation processing is requested, and nothing requests it.
     *
     * @param configuration the configuration of the execution of the compiler that runs this compilation
     * @param project the project being built, whose resolved artifacts must carry the processor
     * @param javac the javac this compilation runs, whose JDK decides whether processing must be requested
     * @param requested whether annotation processing is requested, by {@code -proc:full} or {@code -proc:only} as the
     *     last {@code -proc:} option javac takes, or by a {@code -processor} list
     */
    private static void requireProcessorOnClassPath(CompilerConfiguration configuration, MavenProject project,
            CompilerConfiguration.Javac javac, boolean requested) throws MojoExecutionException {
        for (Artifact artifact : project.getArtifacts()) {
            if (PROCESSOR_GROUP.equals(artifact.getGroupId())
                    && PROCESSOR_ARTIFACT.equals(artifact.getArtifactId())) {
                int release = Jdk.release(javac.home());
                if (release >= REQUESTED_PROCESSING_RELEASE && !requested) {
                    throw new MojoExecutionException(PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " is only a"
                        + " dependency of this project, and the " + configuration.id() + " execution of"
                        + " maven-compiler-plugin, which declares no path in <annotationProcessorPaths>, "
                        + javac.description() + ", a JDK of Java " + release + ": from Java "
                        + REQUESTED_PROCESSING_RELEASE + " on, javac runs no annotation processor it finds on the class"
                        + " path unless annotation processing is requested, so javac would compile the generated"
                        + " vocabulary and publish no index of what it accepted, and the preparation that follows would"
                        + " retire the index of the previous compilation. Configure <proc>full</proc> for that"
                        + " execution, or declare the processor in <annotationProcessorPaths> of that execution, after"
                        + " Lombok when the project uses Lombok.");
                }
                return;
            }
        }
        throw missingProcessor("the dependencies of this project, and the " + configuration.id()
            + " execution of maven-compiler-plugin declares no path in <annotationProcessorPaths>");
    }

    /**
     * Whether a processor path javac is given reaches the class file of the StateGraph annotation processor, as
     * {@link ProcessorPath} looks it up, for the release the {@code release} file of the JDK that javac belongs to
     * declares. When that file declares none this plugin can read, the release is unknown, and a multi-release jar
     * then reaches the class under any of its versioned entries: refusing a build javac might accept would be worse
     * than letting an older javac fail on such a jar after the preparation. The path is split on the path separator,
     * as javac splits it, its empty entries left out, and each entry is resolved where javac runs.
     */
    private static boolean holdsProcessor(CompilerConfiguration configuration, String path,
            CompilerConfiguration.Javac javac) {
        List<Path> entries = new ArrayList<>();
        for (String entry : path.split(Pattern.quote(File.pathSeparator), -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                entries.add(configuration.whereJavacRuns(entry, javac));
            } catch (InvalidPathException unusable) {
                // javac leaves out an entry it cannot name.
            }
        }
        return ProcessorPath.reaches(entries, PROCESSOR_FILE, Jdk.release(javac.home()));
    }

    private static MojoExecutionException noProcessing(CompilerConfiguration configuration, String setting) {
        return new MojoExecutionException(setting + " switches annotation processing off for the "
            + configuration.id() + " execution of maven-compiler-plugin, which StateGraph does not support: "
            + PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " is what publishes the index of what javac"
            + " accepted, so this build would retire the index of the previous compilation and publish"
            + " nothing in its place. Run with annotation processing on.");
    }

    /**
     * Refuses the {@code -processor} list javac takes when it holds a name that is not a valid qualified class name,
     * such as an empty one or one with a space in it, which javac cannot load, or when it leaves out the StateGraph
     * annotation processor.
     *
     * <p>The list is the last {@code -processor} compiler argument, else {@code <annotationProcessors>}, whose
     * values maven-compiler-plugin 3.15.0 hands javac joined by commas, even when there is none, a value Maven left
     * empty reaching javac as {@code null}. javac 21 splits the list on every comma, dropping the empty names at its
     * end, though a list without a comma is one name, even when it is empty; it takes each name exactly as written
     * and stops at the first one it cannot load. A well-formed name of a class that is not on the processor path,
     * such as a misspelt one, passes this check, and javac fails on it after the preparation.</p>
     *
     * @return whether javac takes a {@code -processor} list, which then names the StateGraph annotation processor
     */
    private static boolean requireProcessorListed(CompilerConfiguration configuration) throws MojoExecutionException {
        List<String> arguments = configuration.compilerArguments();
        int option = arguments.lastIndexOf(PROCESSOR_LIST);
        String list;
        String gives;
        String removal;
        if (option >= 0 && option + 1 < arguments.size()) {
            list = arguments.get(option + 1);
            gives = "the compiler argument " + PROCESSOR_LIST + " of the " + configuration.id()
                + " execution of maven-compiler-plugin gives";
            removal = "remove that argument";
        } else {
            Optional<List<String>> declared = configuration.parameterValues("annotationProcessors");
            if (declared.isEmpty()) {
                return false;
            }
            list = String.join(",", declared.get());
            gives = "the <annotationProcessors> of the " + configuration.id()
                + " execution of maven-compiler-plugin give";
            removal = "remove that list, so javac runs every processor on its processor path";
        }
        String[] names = list.split(",");
        for (String name : names) {
            if (!SourceVersion.isName(name)) {
                throw new MojoExecutionException(gives + " javac the processor list '" + list + "', whose name '"
                    + name + "' is not a valid qualified class name, so javac could not load a processor of that"
                    + " name and would fail after the preparation retired the index of the previous compilation."
                    + " javac splits that list on every comma and takes each name exactly as written, spaces"
                    + " included. Correct or remove that name.");
            }
        }
        if (!Arrays.asList(names).contains(PROCESSOR_CLASS)) {
            throw new MojoExecutionException(gives + " javac the processor list '" + list + "', which leaves out "
                + PROCESSOR_CLASS + ", and javac runs only the annotation processors that list names: "
                + PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " is what publishes the index of what javac accepted,"
                + " so this build would retire the index of the previous compilation and publish nothing in its"
                + " place. Add " + PROCESSOR_CLASS + " to that list, after the processors of Lombok when the project"
                + " uses Lombok, or " + removal + '.');
        }
        return true;
    }

    private static MojoExecutionException missingProcessor(String where) {
        return new MojoExecutionException(PROCESSOR_GROUP + ':' + PROCESSOR_ARTIFACT + " is not on " + where
            + ", so javac would compile the generated vocabulary and publish no index of what it accepted,"
            + " and the preparation that follows would retire the index of the previous compilation. Declare it"
            + " in <annotationProcessorPaths> of that execution, after Lombok when the project uses Lombok.");
    }
}
