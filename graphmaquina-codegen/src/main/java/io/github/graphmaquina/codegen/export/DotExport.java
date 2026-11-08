package io.github.graphmaquina.codegen.export;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.annotation.internal.UnboundCollaborator;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.ExportRequest;
import io.github.graphmaquina.codegen.GenerationFiles;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.output.CaseFolding;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;

/**
 * Writes the Graphviz DOT of every machine a compilation published: one file per graph of its published index, named
 * after the qualified name of the machine's facade followed by {@code .dot}, holding what
 * {@code GraphvizExporter.render(GraphView.of(<Machine>Runtime.structure()))} returns.
 *
 * <p>The machines are loaded in a class loader of their own, over the request's runtime classpath and with the
 * platform class loader as its parent, so neither this tool's classes nor its own {@code graphmaquina-core} are
 * visible to them: the view and the rendering are those of the {@code graphmaquina-core} that classpath holds, the
 * application's. Loading a companion initializes it and whatever its {@code structure()} reaches, the enums of its
 * machine among them; its callables never run. An index that lists no machine loads nothing at all, so its
 * classpath need not hold {@code graphmaquina-core}.</p>
 *
 * <p>The index must have been written by this very tool version, as a dependency's is: an index of another version is
 * refused with {@code SG602}, and so is a class output that holds none. Every diagram is rendered before anything is
 * written. Only then is the output directory created if it is missing, emptied of the {@code .dot} files directly in
 * it, so a machine renamed or removed leaves no diagram behind, and filled; nothing else in it is touched. Two
 * diagrams whose names differ only in case, where the volume of the output directory does not tell names apart, are
 * refused with {@code SG603} before anything is written. A companion that cannot be loaded or drawn, such as one
 * whose class initialization throws, is reported with an {@link IllegalStateException} that names it and carries the
 * cause, and nothing is written either.</p>
 */
public final class DotExport {

    /** The extension of a diagram's file. */
    private static final String EXTENSION = ".dot";

    private DotExport() {
    }

    /**
     * Writes the diagram of every machine the compilation of {@code request} published and returns the files written,
     * in the order of the index.
     *
     * @throws CodegenException with {@code SG602} when the class output holds no published index or one of another
     *     tool version, and with {@code SG603} when two diagrams would take one file
     * @throws IllegalStateException when a companion cannot be loaded or drawn, or the classpath holds no
     *     {@code graphmaquina-core}
     * @throws IOException when the index cannot be read or the output directory cannot be written
     */
    public static List<Path> export(@NonNull ExportRequest request) throws IOException {
        ExportIndex index = index(request.classesOutput());
        Map<String, String> diagrams = new LinkedHashMap<>();
        if (!index.model().graphs().isEmpty()) {
            try (URLClassLoader loader = new URLClassLoader(urls(request.classpath()),
                    ClassLoader.getPlatformClassLoader())) {
                Renderer renderer = Renderer.of(loader);
                for (GraphDescriptor graph : index.model().graphs()) {
                    diagrams.put(graph.facade() + EXTENSION,
                            renderer.render(graph.facade(), companion(graph), loader));
                }
            }
        }
        Path output = request.outputDirectory();
        refuseFoldedNames(diagrams, output);
        Files.createDirectories(output);
        removeDiagrams(output);
        List<Path> written = new ArrayList<>();
        for (Map.Entry<String, String> diagram : diagrams.entrySet()) {
            written.add(Files.writeString(output.resolve(diagram.getKey()), diagram.getValue(), StandardCharsets.UTF_8)
                    .toAbsolutePath());
        }
        return List.copyOf(written);
    }

    /** The index {@code classesOutput} publishes, refused when it is missing or another tool version wrote it. */
    private static ExportIndex index(Path classesOutput) throws IOException {
        Path published = classesOutput.resolve(GenerationFiles.PUBLISHED_INDEX);
        if (!Files.isRegularFile(published)) {
            throw new CodegenException(DiagnosticCode.SG602.name(), "the compilation whose classes are in "
                + classesOutput + " publishes no StateGraph index at " + published
                + ": run its StateGraph build, generation to completion, before exporting it");
        }
        ExportIndex index = ManifestCodec.readExports(published);
        String toolVersion = ToolVersion.current();
        if (!index.toolVersion().equals(toolVersion)) {
            throw new CodegenException(DiagnosticCode.SG602.name(), "the StateGraph export index at " + published
                + " was written by tool version " + index.toolVersion() + "; this tool is " + toolVersion);
        }
        return index;
    }

    /** The qualified name of the companion the processor generated for {@code graph}. */
    private static String companion(GraphDescriptor graph) {
        return SymbolNames.qualified(graph.packageName(),
                SymbolNames.runtimeName(SymbolNames.nameWithin(graph.packageName(), graph.facade())));
    }

    private static URL[] urls(List<Path> classpath) throws MalformedURLException {
        URL[] urls = new URL[classpath.size()];
        for (int at = 0; at < urls.length; at++) {
            urls[at] = classpath.get(at).toAbsolutePath().toUri().toURL();
        }
        return urls;
    }

    /**
     * Refuses two diagrams whose file names differ only in case, where the volume of {@code output} does not tell
     * names apart.
     */
    private static void refuseFoldedNames(Map<String, String> diagrams, Path output) throws IOException {
        if (!CaseFolding.folds(output)) {
            return;
        }
        Map<String, String> folded = new HashMap<>();
        for (String name : diagrams.keySet()) {
            String other = folded.putIfAbsent(CaseFolding.key(name), name);
            if (other != null) {
                throw new CodegenException(DiagnosticCode.SG603.name(), "the diagrams " + other + " and " + name
                    + " would take one file in " + output + ", whose volume does not tell names apart by case:"
                    + " rename one of the two machines' facades");
            }
        }
    }

    /** Deletes the {@code .dot} entries directly in {@code output} that are no directory. */
    private static void removeDiagrams(Path output) throws IOException {
        List<Path> diagrams = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(output, "*" + EXTENSION)) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    diagrams.add(entry);
                }
            }
        }
        for (Path diagram : diagrams) {
            Files.delete(diagram);
        }
    }

    /** {@code GraphView.of} and {@code GraphvizExporter.render}, as the {@code graphmaquina-core} of a loader has them. */
    private record Renderer(Method view, Method render) {

        /** The two methods of the {@code graphmaquina-core} that {@code loader} holds. */
        static Renderer of(ClassLoader loader) {
            try {
                Class<?> definition = Class.forName(GraphDefinition.class.getName(), false, loader);
                Class<?> view = Class.forName(GraphView.class.getName(), true, loader);
                Class<?> exporter = Class.forName(GraphvizExporter.class.getName(), true, loader);
                return new Renderer(view.getMethod("of", definition), exporter.getMethod("render", view));
            } catch (ReflectiveOperationException | LinkageError absent) {
                throw new IllegalStateException("the runtime classpath of the compilation holds no usable"
                    + " graphmaquina-core to draw its machines with", absent);
            }
        }

        /** The DOT of the definition {@code companion}'s {@code structure()} builds, for the machine {@code facade}. */
        String render(String facade, String companion, ClassLoader loader) {
            try {
                Method structure = Class.forName(companion, true, loader).getMethod(UnboundCollaborator.STRUCTURE);
                return (String) render.invoke(null, view.invoke(null, structure.invoke(null)));
            } catch (InvocationTargetException failed) {
                throw new IllegalStateException(cannotDraw(facade, companion), failed.getCause());
            } catch (ReflectiveOperationException | LinkageError failed) {
                throw new IllegalStateException(cannotDraw(facade, companion), failed);
            }
        }

        private static String cannotDraw(String facade, String companion) {
            return "cannot draw the machine " + facade + " through " + companion + "." + UnboundCollaborator.STRUCTURE
                + "()";
        }
    }
}
