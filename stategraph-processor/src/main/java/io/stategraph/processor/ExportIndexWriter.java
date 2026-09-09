package io.stategraph.processor;

import io.stategraph.codegen.CodegenException;
import io.stategraph.codegen.manifest.CompileManifest;
import io.stategraph.codegen.manifest.ExportIndex;
import io.stategraph.codegen.manifest.ManifestCodec;
import io.stategraph.codegen.model.GraphDescriptor;
import io.stategraph.codegen.model.SymbolDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Writes what this compilation may publish once javac has accepted it: the provisional index of the local
 * exports and the inventory of the sources the processor generated.
 *
 * <p>Neither file is the published index. {@code META-INF/stategraph/pending-exports.json} carries exactly the
 * local index of the manifest — never the dependencies, which belong to the producers that published them — and
 * only the build's own completion step promotes it to {@code exports.json}, after javac has succeeded. A
 * dependent therefore never reads an index describing declarations that failed to compile, and this processor
 * never has to guess whether the compilation it is part of will end well.</p>
 *
 * <p>The index is written through the {@code Filer}, so it lands in the class output of the compilation
 * wherever the build put it, and its bytes come from {@link ManifestCodec}, which stays the only writer of that
 * format: a second encoder would be a second definition of the schema. The inventory is a file of the build,
 * not an output of the compilation, so it is written where the manifest says the build will look for it.</p>
 *
 * <p>An index is published even when the compilation declares no machine at all: the carrier of the
 * compilation is still there, and a dependent that finds no index cannot tell an empty producer from one whose
 * publication was lost. A failure to write either file is reported where it happens; the writer runs after
 * everything else, so nothing reads a count of errors afterwards.</p>
 */
final class ExportIndexWriter {

    private static final String PENDING = "META-INF/stategraph/pending-exports.json";
    private static final String FAILED = "SG599";
    private static final int SCHEMA_VERSION = 1;

    private final ProcessingEnvironment environment;
    private final Diagnostics diagnostics;

    ExportIndexWriter(ProcessingEnvironment environment) {
        this.environment = environment;
        this.diagnostics = new Diagnostics(environment.getMessager());
    }

    /**
     * Publishes the local index of {@code manifest} and the inventory of {@code generatedQualifiedNames}.
     * {@code verifiedLocalTypes} are the local declarations this compilation checked; nothing is written when
     * the index would name one that was not among them, since the index states what this compilation verified.
     */
    void publish(CompileManifest manifest, Set<String> verifiedLocalTypes, Set<String> generatedQualifiedNames) {
        if (!verified(manifest.local(), verifiedLocalTypes) || !writeIndex(manifest.local())) {
            return;
        }
        writeInventory(manifest.processorInventory(), generatedQualifiedNames);
    }

    /** Whether every machine and every symbol {@code index} names was verified by this compilation. */
    private static boolean verified(ExportIndex index, Set<String> verifiedLocalTypes) {
        for (GraphDescriptor graph : index.model().graphs()) {
            if (!verifiedLocalTypes.contains(graph.facade())) {
                return false;
            }
        }
        for (SymbolDescriptor symbol : index.model().symbols()) {
            if (!verifiedLocalTypes.contains(symbol.qualifiedName())) {
                return false;
            }
        }
        return true;
    }

    /** The local index, encoded by the codec and copied into the resource the Filer creates for it. */
    private boolean writeIndex(ExportIndex index) {
        try {
            byte[] encoded = encode(index);
            FileObject resource = environment.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", PENDING);
            try (OutputStream out = resource.openOutputStream()) {
                out.write(encoded);
            }
            return true;
        } catch (IOException | CodegenException failure) {
            diagnostics.error(FAILED, "could not write " + PENDING + ": " + failure.getMessage());
            return false;
        }
    }

    /** The bytes of {@code index}, written by the codec to a file of this run and read back. */
    private static byte[] encode(ExportIndex index) throws IOException {
        Path staged = Files.createTempFile("stategraph-pending-exports", ".json");
        try {
            ManifestCodec.writeExports(staged, index);
            return Files.readAllBytes(staged);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * The qualified names of every source the processor generated, sorted, at the path the manifest names.
     * The build reads it to know which outputs of the compilation this processor owns; the names are Java
     * qualified names, so nothing in them needs escaping.
     */
    private void writeInventory(String inventory, Set<String> generatedQualifiedNames) {
        StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": ").append(SCHEMA_VERSION)
                .append(",\n  \"generatedTypes\": [");
        String separator = "\n    ";
        for (String name : new TreeSet<>(generatedQualifiedNames)) {
            json.append(separator).append('"').append(name).append('"');
            separator = ",\n    ";
        }
        json.append(generatedQualifiedNames.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        try {
            Path file = Path.of(inventory);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException failure) {
            diagnostics.error(FAILED, "could not write the processor inventory " + inventory + ": "
                    + failure.getMessage());
        }
    }
}
