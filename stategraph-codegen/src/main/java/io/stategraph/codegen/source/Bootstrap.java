package io.stategraph.codegen.source;

import io.stategraph.codegen.GenerationRequest;
import io.stategraph.codegen.model.GenerationModel;
import io.stategraph.codegen.model.GraphDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Reads one generation request into the model the emitters work from, without compiling the application.
 *
 * <p>The original sources are only parsed; their headers, the classpath headers and the marked declarations
 * they carry are enough to resolve every machine and every exported symbol. Machines and symbols are produced
 * in a deterministic order, so two runs over the same sources give the same model whatever order the build
 * happened to list its files in.</p>
 */
public final class Bootstrap {

    private static final String GENERATE_GRAPH_API = "io.stategraph.annotation.GenerateGraphApi";

    private Bootstrap() {
    }

    /** The graphs and symbols {@code request} declares, resolved against its sources and its classpath. */
    public static GenerationModel inspect(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        List<SourceUnit> units = SourceDeclarations.parse(request.projectDirectory(), sources(request));
        try (DeclarationIndex index = DeclarationIndex.open(units, request.classpath())) {
            List<GraphDescriptor> graphs = new ArrayList<>();
            for (String facade : machines(index)) {
                graphs.add(MachineHeaders.resolve(index, facade));
            }
            return new GenerationModel(graphs, ExportDeclarations.collect(index));
        }
    }

    /** The original declarations marked with {@code @GenerateGraphApi}, ordered by canonical name. */
    private static List<String> machines(DeclarationIndex index) {
        List<String> facades = new ArrayList<>();
        for (TypeHeader header : index.sourceHeaders()) {
            for (TypeHeader.Marker marker : header.markers()) {
                if (index.marks(header, marker, GENERATE_GRAPH_API)) {
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
     * {@code .java} files in sorted order. An entry that does not exist contributes nothing, so a source set
     * whose directory the build has not created yet is not a failure.
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
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read the original sources under " + directory, failure);
        }
    }
}
