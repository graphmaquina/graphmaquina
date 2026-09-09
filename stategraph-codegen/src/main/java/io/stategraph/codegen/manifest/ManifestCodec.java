package io.stategraph.codegen.manifest;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import io.stategraph.codegen.CodegenException;
import io.stategraph.codegen.GenerationRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Reads and writes the generator's manifests as deterministic, UTF-8, LF-terminated JSON. This is the only JSON
 * boundary of the {@code io.stategraph.codegen} module: every other type in this package and in
 * {@code io.stategraph.codegen.model} is a plain value read or written only through this class.
 *
 * <p>Every read method parses the document with a strict {@link com.google.gson.stream.JsonReader}: unknown
 * {@code schemaVersion} values, a missing mandatory member, a member of the wrong JSON type, a duplicate key at
 * any nesting level, and an inventory path ({@link CompileManifest#ownedSources()}) that is absolute or escapes
 * its root through a {@code ..} segment are all rejected with a {@link CodegenException} carrying code
 * {@code SG602}. A genuine I/O failure (the file cannot be read or written, or its low-level JSON is malformed)
 * is reported as an {@link IOException} instead, exactly as declared.
 *
 * <p>Every write method sorts identity lists (a model's graphs by {@code facade}, its symbols by
 * {@code qualifiedName}, a symbol's {@code kinds} and {@code candidates} by name, an index's
 * {@code annotationTypes}, a manifest's {@code dependencies} by {@code compilationId} and its
 * {@code ownedSources}) so that two equal values always serialize to byte-identical files; a graph's
 * {@code states} and a member's {@code parameterTypes} keep the order they were given in, since that order is
 * semantically significant. A {@link io.stategraph.codegen.model.SymbolDescriptor#property()} that is empty is
 * omitted from the JSON entirely, never written as {@code null}.
 *
 * <h2>{@code GenerationRequest} JSON (readRequest/writeRequest)</h2>
 *
 * <p>A flat JSON object whose members are exactly the record's components, so that a build plugin can produce
 * it with its own minimal JSON encoder without depending on this module:
 *
 * <pre>{@code
 * {
 *   "projectDirectory": "<string, Path.toString()>",
 *   "sources": ["<string, Path.toString()>", ...],
 *   "classpath": ["<string, Path.toString()>", ...],
 *   "sourcesOutput": "<string, Path.toString()>",
 *   "processorSourcesOutput": "<string, Path.toString()>",
 *   "classesOutput": "<string, Path.toString()>",
 *   "workDirectory": "<string, Path.toString()>",
 *   "compilationId": "<string>"
 * }
 * }</pre>
 *
 * <h2>{@code ExportIndex} JSON (readExports/writeExports)</h2>
 *
 * <pre>{@code
 * {
 *   "schemaVersion": <number, currently 1>,
 *   "toolVersion": "<string>",
 *   "compilationId": "<string>",
 *   "model": {
 *     "graphs": [
 *       {
 *         "facade": "<string>", "packageName": "<string, empty for the unnamed package>",
 *         "entityType": "<string>", "stateType": "<string>",
 *         "states": ["<string>", ...], "peers": ["<string>", ...],
 *         "origin": {"path": "<string>", "line": <number>, "column": <number>}
 *       }, ...
 *     ],
 *     "symbols": [
 *       {
 *         "qualifiedName": "<string>", "host": "<string>", "member": "<string>",
 *         "kinds": ["<string, SymbolKind name>", ...],
 *         "candidates": [
 *           {
 *             "declaringType": "<string>", "name": "<string>", "parameterTypes": ["<string>", ...],
 *             "returnType": "<string>", "role": "<string, SymbolKind name>", "staticMember": <boolean>,
 *             "origin": {"path": "<string>", "line": <number>, "column": <number>}
 *           }, ...
 *         ],
 *         "property": {
 *           "declaringType": "<string>", "logicalName": "<string>", "declarationName": "<string>",
 *           "directGetter": <boolean>, "origin": {"path": "<string>", "line": <number>, "column": <number>}
 *         },
 *         "origin": {"path": "<string>", "line": <number>, "column": <number>}
 *       }, ...
 *     ]
 *   },
 *   "annotationTypes": ["<string>", ...]
 * }
 * }</pre>
 *
 * <p>{@code property} is present only when the symbol resolved to a {@code @GraphProperty} member; it is
 * omitted otherwise.
 *
 * <h2>{@code CompileManifest} JSON (readCompile/writeCompile)</h2>
 *
 * <pre>{@code
 * {
 *   "schemaVersion": <number, currently 1>,
 *   "toolVersion": "<string>",
 *   "local": { ...an ExportIndex, as above... },
 *   "dependencies": [ { ...an ExportIndex, as above... }, ... ],
 *   "processorInventory": "<string, absolute execution path of the processor's inventory>",
 *   "ownedSources": ["<string, path relative to its source root>", ...]
 * }
 * }</pre>
 */
public final class ManifestCodec {

    private ManifestCodec() {
    }

    /**
     * Reads a {@link GenerationRequest} from the flat JSON object at {@code path}.
     *
     * @throws IOException if {@code path} cannot be read
     */
    public static GenerationRequest readRequest(Path path) throws IOException {
        JsonObject object = JsonSupport.readObject(path);
        Path projectDirectory = Path.of(JsonSupport.requireString(object, "projectDirectory", path));
        List<Path> sources = toPaths(JsonSupport.requireStringArray(object, "sources", path));
        List<Path> classpath = toPaths(JsonSupport.requireStringArray(object, "classpath", path));
        Path sourcesOutput = Path.of(JsonSupport.requireString(object, "sourcesOutput", path));
        Path processorSourcesOutput = Path.of(JsonSupport.requireString(object, "processorSourcesOutput", path));
        Path classesOutput = Path.of(JsonSupport.requireString(object, "classesOutput", path));
        Path workDirectory = Path.of(JsonSupport.requireString(object, "workDirectory", path));
        String compilationId = JsonSupport.requireString(object, "compilationId", path);
        return new GenerationRequest(projectDirectory, sources, classpath, sourcesOutput,
            processorSourcesOutput, classesOutput, workDirectory, compilationId);
    }

    /**
     * Writes {@code request} as the flat JSON object documented on this class.
     *
     * @throws IOException if {@code path} cannot be written
     */
    public static void writeRequest(Path path, GenerationRequest request) throws IOException {
        try (JsonWriter writer = JsonSupport.newWriter(path)) {
            writer.beginObject();
            writer.name("projectDirectory").value(request.projectDirectory().toString());
            writer.name("sources");
            JsonSupport.writeStringArray(writer, toStrings(request.sources()));
            writer.name("classpath");
            JsonSupport.writeStringArray(writer, toStrings(request.classpath()));
            writer.name("sourcesOutput").value(request.sourcesOutput().toString());
            writer.name("processorSourcesOutput").value(request.processorSourcesOutput().toString());
            writer.name("classesOutput").value(request.classesOutput().toString());
            writer.name("workDirectory").value(request.workDirectory().toString());
            writer.name("compilationId").value(request.compilationId());
            writer.endObject();
        }
    }

    /**
     * Reads an {@link ExportIndex} from {@code path}.
     *
     * @throws IOException if {@code path} cannot be read
     */
    public static ExportIndex readExports(Path path) throws IOException {
        return readExportIndex(JsonSupport.readObject(path), path);
    }

    /**
     * Writes {@code index} to {@code path}, in the canonical order documented on this class.
     *
     * @throws IOException if {@code path} cannot be written
     */
    public static void writeExports(Path path, ExportIndex index) throws IOException {
        try (JsonWriter writer = JsonSupport.newWriter(path)) {
            writeExportIndex(writer, index);
        }
    }

    /**
     * Reads a {@link CompileManifest} from {@code path}.
     *
     * @throws IOException if {@code path} cannot be read
     */
    public static CompileManifest readCompile(Path path) throws IOException {
        JsonObject object = JsonSupport.readObject(path);
        int schemaVersion = JsonSupport.requireInt(object, "schemaVersion", path);
        JsonSupport.requireSupportedSchema(schemaVersion, path);
        String toolVersion = JsonSupport.requireString(object, "toolVersion", path);
        ExportIndex local = readExportIndex(JsonSupport.requireObject(object, "local", path), path);
        List<ExportIndex> dependencies = JsonSupport.requireArray(object, "dependencies", path).asList().stream()
            .map(element -> readExportIndex(JsonSupport.asObject(element, path), path))
            .toList();
        String processorInventory = JsonSupport.requireString(object, "processorInventory", path);
        List<String> ownedSources = JsonSupport.requireStringArray(object, "ownedSources", path);
        for (String ownedSource : ownedSources) {
            JsonSupport.requireRelativeInventoryPath(ownedSource, path);
        }
        return new CompileManifest(schemaVersion, toolVersion, local, dependencies, processorInventory, ownedSources);
    }

    /**
     * Writes {@code manifest} to {@code path}, in the canonical order documented on this class.
     *
     * @throws IOException if {@code path} cannot be written
     */
    public static void writeCompile(Path path, CompileManifest manifest) throws IOException {
        for (String ownedSource : manifest.ownedSources()) {
            JsonSupport.requireRelativeInventoryPath(ownedSource, path);
        }
        try (JsonWriter writer = JsonSupport.newWriter(path)) {
            writer.beginObject();
            writer.name("schemaVersion").value(manifest.schemaVersion());
            writer.name("toolVersion").value(manifest.toolVersion());
            writer.name("local");
            writeExportIndex(writer, manifest.local());
            writer.name("dependencies");
            writer.beginArray();
            for (ExportIndex dependency : manifest.dependencies().stream()
                    .sorted(Comparator.comparing(ExportIndex::compilationId)).toList()) {
                writeExportIndex(writer, dependency);
            }
            writer.endArray();
            writer.name("processorInventory").value(manifest.processorInventory());
            writer.name("ownedSources");
            JsonSupport.writeStringArray(writer, JsonSupport.sortedCopy(manifest.ownedSources()));
            writer.endObject();
        }
    }

    private static ExportIndex readExportIndex(JsonObject object, Path file) {
        int schemaVersion = JsonSupport.requireInt(object, "schemaVersion", file);
        JsonSupport.requireSupportedSchema(schemaVersion, file);
        String toolVersion = JsonSupport.requireString(object, "toolVersion", file);
        String compilationId = JsonSupport.requireString(object, "compilationId", file);
        return new ExportIndex(schemaVersion, toolVersion, compilationId,
            GenerationModelJson.read(JsonSupport.requireObject(object, "model", file), file),
            JsonSupport.requireStringArray(object, "annotationTypes", file));
    }

    private static void writeExportIndex(JsonWriter writer, ExportIndex index) throws IOException {
        writer.beginObject();
        writer.name("schemaVersion").value(index.schemaVersion());
        writer.name("toolVersion").value(index.toolVersion());
        writer.name("compilationId").value(index.compilationId());
        writer.name("model");
        GenerationModelJson.write(writer, index.model());
        writer.name("annotationTypes");
        JsonSupport.writeStringArray(writer, JsonSupport.sortedCopy(index.annotationTypes()));
        writer.endObject();
    }

    private static List<Path> toPaths(List<String> values) {
        return values.stream().map(Path::of).toList();
    }

    private static List<String> toStrings(List<Path> values) {
        return values.stream().map(Path::toString).toList();
    }
}
