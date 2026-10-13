package io.github.graphmaquina.processor;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Writes what this compilation may publish once javac has accepted it: the provisional index of the local
 * exports and the inventory of the sources the processor generated.
 *
 * <p>Neither file is the published index. {@code META-INF/graphmaquina/pending-exports.json} carries exactly the
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
 * publication was lost. A failure to write either file is reported where it happens, and so is an index that
 * would name a declaration this compilation never processed, which is published not at all; the writer runs
 * after everything else, so nothing reads a count of errors afterwards. Nor is an index published that would name
 * a machine this compilation was handed but did not verify, and that says nothing: when this processor reported no
 * error, only a value of that machine's own declarations that no round resolved leaves it unverified, and javac
 * refuses that value itself once processing is over.</p>
 */
final class ExportIndexWriter {

    private static final String PENDING = "META-INF/graphmaquina/pending-exports.json";
    private static final String FAILED = "SG599";
    private static final String UNPROCESSED = "SG602";

    private final ProcessingEnvironment environment;
    private final Diagnostics diagnostics;

    ExportIndexWriter(ProcessingEnvironment environment) {
        this.environment = environment;
        this.diagnostics = new Diagnostics(environment);
    }

    /**
     * Publishes the local index of {@code manifest} and the inventory of {@code generatedQualifiedNames}.
     * {@code verifiedLocalTypes} are the local declarations this compilation checked; nothing is written when
     * the index would name one that was not among them, since the index states what this compilation verified.
     * One that is not among {@code handedMachines} either, the machines some round handed to the processor, is
     * reported as {@code SG602}: such a declaration was never processed, which is what happens to a source javac
     * compiles implicitly, from the source path, instead of being handed it. One that was handed is reported by
     * javac, which refuses, once processing is over, a value of its declarations that no round resolved.
     */
    void publish(CompileManifest manifest, Set<String> verifiedLocalTypes, Set<String> handedMachines,
            Set<String> generatedQualifiedNames) {
        Set<String> unverified = unverified(manifest.local(), verifiedLocalTypes);
        Set<String> unprocessed = new LinkedHashSet<>(unverified);
        unprocessed.removeAll(handedMachines);
        if (!unprocessed.isEmpty()) {
            diagnostics.error(UNPROCESSED, "the index of this compilation names " + String.join(", ", unprocessed)
                    + ", which this compilation never processed: javac does not process a source it compiles"
                    + " implicitly, from the source path, so pass every source of the compilation to javac");
            return;
        }
        if (!unverified.isEmpty()) {
            return;
        }
        if (!writeIndex(manifest.local())) {
            return;
        }
        writeInventory(manifest.processorInventory(), generatedQualifiedNames);
    }

    /** Every machine and every symbol {@code index} names that this compilation did not verify, in index order. */
    private static Set<String> unverified(ExportIndex index, Set<String> verifiedLocalTypes) {
        Set<String> unverified = new LinkedHashSet<>();
        for (GraphDescriptor graph : index.model().graphs()) {
            if (!verifiedLocalTypes.contains(graph.facade())) {
                unverified.add(graph.facade());
            }
        }
        for (SymbolDescriptor symbol : index.model().symbols()) {
            if (!verifiedLocalTypes.contains(symbol.qualifiedName())) {
                unverified.add(symbol.qualifiedName());
            }
        }
        return unverified;
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
        Path staged = Files.createTempFile("graphmaquina-pending-exports", ".json");
        try {
            ManifestCodec.writeExports(staged, index);
            return Files.readAllBytes(staged);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * The qualified names of every source the processor generated, at the path the manifest names. The build
     * reads it to know which outputs of the compilation this processor owns, and the codec writes it, so the
     * format has exactly one definition.
     */
    private void writeInventory(String inventory, Set<String> generatedQualifiedNames) {
        try {
            Path file = Path.of(inventory);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ManifestCodec.writeProcessorInventory(file, List.copyOf(generatedQualifiedNames));
        } catch (IOException | InvalidPathException failure) {
            diagnostics.error(FAILED, "could not write the processor inventory " + inventory + ": "
                    + failure.getMessage());
        }
    }
}
