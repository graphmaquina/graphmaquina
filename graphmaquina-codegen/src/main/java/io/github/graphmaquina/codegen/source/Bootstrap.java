package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Reads one generation request into the model the emitters work from, without compiling the application.
 *
 * <p>The original sources are only parsed; their headers, the classpath headers and the marked declarations
 * they carry are enough to resolve every machine and every exported symbol. Machines and symbols are produced
 * in a deterministic order, so two runs over the same sources give the same model whatever order the build
 * happened to list its files in.</p>
 */
public final class Bootstrap {

    private static final String GENERATE_GRAPH_API = "io.github.graphmaquina.annotation.GenerateGraphApi";

    private Bootstrap() {
    }

    /**
     * The graphs and symbols {@code request} declares, resolved against its sources and its classpath. A
     * declaration the generated vocabulary or references could not name from where they are generated is
     * refused here, before anything is emitted.
     */
    public static GenerationModel inspect(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        List<SourceUnit> units = SourceDeclarations.parse(request.projectDirectory(), sources(request));
        try (DeclarationIndex index = DeclarationIndex.open(units, request.classpath())) {
            List<GraphDescriptor> graphs = new ArrayList<>();
            for (String facade : machines(index)) {
                graphs.add(MachineHeaders.resolve(index, facade));
            }
            MachineHeaders.requireWatchablePeers(index, graphs);
            return new GenerationModel(graphs, ExportDeclarations.collect(index));
        }
    }

    /** The original declarations marked with {@code @GenerateGraphApi}, ordered by canonical name. */
    private static List<String> machines(DeclarationIndex index) {
        List<String> facades = new ArrayList<>();
        for (TypeHeader header : index.sourceHeaders()) {
            for (TypeHeader.Marker marker : header.markers()) {
                if (index.marksInHeader(header, marker, GENERATE_GRAPH_API)) {
                    facades.add(header.canonicalName());
                    break;
                }
            }
        }
        facades.sort(String::compareTo);
        return facades;
    }

    /**
     * Expands the requested sources: a file is read as it is given, a directory is walked recursively for its
     * {@code .java} files in sorted order, following symbolic links as Gradle's walk of a source set does. An
     * entry that does not exist contributes nothing, so a source set whose directory the build has not created
     * yet is not a failure. An entry that leads back into a directory the walk is already inside is skipped, as
     * Gradle skips it, so the walk always ends. Every other link is followed wherever it points: a link out of the
     * directory brings in the sources it reaches, and a file reached through two paths is read through each of
     * them, its declarations recorded under the first of those paths in sorted order.
     */
    private static List<Path> sources(GenerationRequest request) {
        List<Path> files = new ArrayList<>();
        for (Path source : request.sources()) {
            if (Files.isDirectory(source)) {
                files.addAll(javaFiles(source));
            } else if (Files.isRegularFile(source)) {
                files.add(source);
            }
        }
        return List.copyOf(files);
    }

    private static List<Path> javaFiles(Path directory) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(directory, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                                files.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                            if (failure instanceof FileSystemLoopException) {
                                return FileVisitResult.CONTINUE;
                            }
                            throw failure;
                        }
                    });
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read the original sources under " + directory, failure);
        }
        files.sort(Comparator.<Path>naturalOrder());
        return List.copyOf(files);
    }
}
