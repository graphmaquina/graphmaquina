package io.github.graphmaquina.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileVisitDetails;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The check that the generation and the compilation of one source set read the same original sources.
 *
 * <p>The generation reads the directories of the source set as the include and exclude patterns of the source set and
 * the include and exclude pattern strings of the compilation task filter them. javac compiles whatever the compilation
 * task's own source holds, as all of its filters select it: a filter given to the task as a spec or a closure, or a
 * source added to the task or put in place of the source set's, can make the two differ. A generation that reads a
 * source javac does not compile may generate a vocabulary javac finds no declaration for, or fail on a draft javac
 * never sees, and one that misses a source javac compiles generates nothing for it; when the Java sources the two read
 * differ, the generation is refused before the generator runs, and the preparation refuses the compilation before it
 * retires anything when the generation is up to date.</p>
 */
final class OriginalSources {

    private OriginalSources() {
    }

    /**
     * Every file of {@code tree}, with the directory a visit of that tree gives its path relative to: the source
     * directory a compilation takes it from, or, for a file it is given on its own, the directory that holds it.
     */
    static Map<File, File> withRoots(FileTree tree) {
        Map<File, File> roots = new HashMap<>();
        tree.visit((FileVisitDetails details) -> {
            if (!details.isDirectory()) {
                File root = details.getFile();
                for (int segment = 0; segment < details.getRelativePath().getSegments().length; segment++) {
                    root = root.getParentFile();
                }
                roots.put(details.getFile(), root);
            }
        });
        return roots;
    }

    /**
     * Refuses a generation whose original Java sources are not the ones javac compiles. Only Java sources outside
     * the given generated directories are compared, so the generated sources and those the annotation processor
     * writes count on neither side.
     *
     * @param read the files the generation reads, its whole directories walked
     * @param compiled the files the compilation task compiles, each with the directory it takes it from
     * @param generatedDirectories the directories the generation and the annotation processor write sources into
     * @param generation the name of the generation task
     * @param compilation the compilation task, as the refusal names it
     * @throws GradleException naming the first original Java source one of the two reads and the other does not
     */
    static void requireSame(Collection<File> read, Map<File, File> compiled, Collection<File> generatedDirectories,
            String generation, String compilation) {
        Set<File> readSources = new TreeSet<>();
        for (File file : read) {
            if (original(file, generatedDirectories)) {
                readSources.add(file);
            }
        }
        Map<File, File> compiledSources = new TreeMap<>();
        for (Map.Entry<File, File> file : compiled.entrySet()) {
            if (original(file.getKey(), generatedDirectories)) {
                compiledSources.put(file.getKey(), file.getValue());
            }
        }
        for (File source : readSources) {
            if (!compiledSources.containsKey(source)) {
                throw new GradleException(notCompiled(source, new TreeSet<>(compiledSources.values()), generation,
                        compilation));
            }
        }
        for (Map.Entry<File, File> source : compiledSources.entrySet()) {
            if (!readSources.contains(source.getKey())) {
                throw new GradleException(compilation + " compiles " + source.getKey() + ", which the StateGraph"
                        + " generation " + generation + " does not read. The generation reads the java sources of the"
                        + " source set alone, so it never sees a source " + compilation + " is given beside them or in"
                        + " place of them. Give " + source.getValue() + ", the directory " + compilation + " takes that"
                        + " file from, to the java sources of the source set instead, with java.srcDir(...), and remove"
                        + " it from the sources of " + compilation + ".");
            }
        }
    }

    /**
     * Why {@code compilation} does not compile {@code source}, which the generation reads, and the two ways back. For a
     * filter, giving it to the source set or as pattern strings. For a source given in place of the source set's,
     * giving the source set {@code compiledDirectories}, the directories the compilation takes its original sources
     * from, in place of its own, with the generated directory added back after them, as both build script languages
     * spell it, fully qualified so either line can be pasted as it is: replacing the source set's directories drops
     * the generated one, which the preparation would refuse in turn.
     */
    private static String notCompiled(File source, Set<File> compiledDirectories, String generation,
            String compilation) {
        List<String> kotlin = new ArrayList<>();
        List<String> groovy = new ArrayList<>();
        for (File directory : compiledDirectories) {
            kotlin.add(kotlinString(directory.getPath()));
            groovy.add(groovyString(directory.getPath()));
        }
        String directories = compiledDirectories.isEmpty() ? "none" : compiledDirectories.stream()
                .map(File::getPath).collect(Collectors.joining(", "));
        return "the StateGraph generation " + generation + " reads " + source + ", which " + compilation + " does not"
                + " compile. The generation reads the directories of the source set as the include and exclude"
                + " patterns of the source set and the include and exclude pattern strings of " + compilation
                + " filter them, so it cannot follow a filter " + compilation + " is given as a spec or a closure, nor"
                + " a source " + compilation + " is given in place of the source set's. When " + compilation + " is"
                + " given such a filter, give it to the java sources of the source set instead, where a spec or a"
                + " closure is followed too, or give it to " + compilation + " as an include or exclude pattern. When "
                + compilation
                + " is given a source in place of the source set's, give the source set the directories " + compilation
                + " compiles, " + directories + ", in place of its own, add the generated directory back after them,"
                + " and remove that source from " + compilation + "; inside the source set's block:"
                + System.lineSeparator()
                + "    Kotlin DSL: java.setSrcDirs(listOf<String>(" + String.join(", ", kotlin) + ")); "
                + CompilationLifecycle.kotlinGeneratedSourcesLine(generation) + System.lineSeparator()
                + "    Groovy DSL: java.srcDirs = [" + String.join(", ", groovy) + "]; "
                + CompilationLifecycle.groovyGeneratedSourcesLine(generation);
    }

    /** {@code value} as a Kotlin string literal. */
    private static String kotlinString(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + '"';
    }

    /** {@code value} as a Groovy string literal that interpolates nothing. */
    private static String groovyString(String value) {
        return '\'' + value.replace("\\", "\\\\").replace("'", "\\'") + '\'';
    }

    /** Whether {@code file} is a Java source outside every one of {@code generatedDirectories}. */
    private static boolean original(File file, Collection<File> generatedDirectories) {
        if (!file.getName().endsWith(".java")) {
            return false;
        }
        Path path = file.toPath();
        for (File directory : generatedDirectories) {
            if (path.startsWith(directory.toPath())) {
                return false;
            }
        }
        return true;
    }
}
