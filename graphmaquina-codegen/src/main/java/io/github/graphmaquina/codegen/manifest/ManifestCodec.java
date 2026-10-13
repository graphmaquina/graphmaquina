package io.github.graphmaquina.codegen.manifest;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Reads and writes the generator's manifests as deterministic UTF-8 JSON, indented by two spaces with LF line
 * breaks; only the processor inventory ends with one. This is the only public JSON boundary of the
 * {@code io.github.graphmaquina.codegen} module, with two package-private helpers behind it: the manifests of this
 * package and the model of {@code io.github.graphmaquina.codegen.model} are plain values, read and written as JSON
 * only through this class.
 *
 * <p>Schema 2 is the only schema this tool reads, including inventories of owned outputs. A document of
 * another schema is refused with {@code SG602} before its model or owned paths are used. There is no conversion,
 * partial model reading or cleanup using an inventory of another schema.
 *
 * <p>Every read method parses the document with a strict {@link com.google.gson.stream.JsonReader}: a document
 * that is not well-formed strict JSON, holds bytes that are not UTF-8 text, escapes a surrogate that is not half of
 * a pair in a string or a name, or carries content after its end, unknown {@code schemaVersion} values, a missing
 * mandatory member, a member of the wrong JSON type, a number
 * member that is not an exact integer of its range (a fraction, or a value beyond 32 bits for
 * {@code schemaVersion} and 64 bits for an origin's {@code line} and {@code column}), a number anywhere in the
 * document whose exponent lies beyond the range a {@link java.math.BigDecimal} holds, such as
 * {@code 1e99999999999}, a duplicate key at any nesting level, a processor inventory
 * ({@link CompileManifest#processorInventory()}) that no path can spell, such as one holding the NUL character, and
 * an inventory path ({@link CompileManifest#ownedSources()}) that no path can spell, that is absolute or has a root,
 * that holds a {@code ..} segment, that names the root itself, as {@code .} does, or that is no Java source, as
 * {@code x} is not, are all rejected with a
 * {@link CodegenException} carrying code {@code SG602} and naming the file. A genuine I/O failure
 * (the file cannot be opened, read or written) is reported as an {@link IOException} instead, exactly as declared.
 *
 * <p>Every write method sorts identity lists (a model's graphs by {@code facade}, its symbols by
 * {@code qualifiedName}, a graph's {@code peers}, a symbol's {@code kinds} by name and its {@code candidates} by
 * name, then by their parameter types, an index's {@code annotationTypes}, a manifest's {@code dependencies} by
 * {@code compilationId} and its {@code ownedSources}, the four lists of an owned-outputs inventory and the
 * processor inventory's {@code generatedTypes}) so that two equal values always serialize to byte-identical files;
 * a graph's {@code states}, its {@code transitions}, a member's {@code parameterTypes} and a request's
 * {@code sources} and {@code classpath} keep the order they were given in, since that order is semantically
 * significant. A
 * {@link io.github.graphmaquina.codegen.model.SymbolDescriptor#property()} that is empty is omitted from the
 * JSON entirely, never written as {@code null}.
 *
 * <h2>{@code GenerationRequest} JSON (readRequest/writeRequest)</h2>
 *
 * <p>A flat JSON object whose members are the record's components, so that a build plugin can produce it with
 * its own minimal JSON encoder without depending on this module:
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
 *   "compilationId": "<string>",
 *   "release": <number, optional: the Java release javac compiles at>,
 *   "encoding": "<string, optional: the name of the charset javac reads the sources in>"
 * }
 * }</pre>
 *
 * <p>{@code release} and {@code encoding} are optional: a request without them is read at release
 * {@value GenerationRequest#DEFAULT_RELEASE} in UTF-8. A {@code release} that is not an exact integer, and an
 * {@code encoding} that names no charset of the running JVM, are rejected with
 * {@code SG602}. {@link #writeRequest} always writes both. No member carries {@code --enable-preview}: the
 * generator parses the original sources without it, whatever javac is given. An original source using a preview
 * feature that javac's parser checks, such as string templates on JDK 21, is refused with {@code SG601}; one using
 * a preview feature javac checks only after parsing, such as primitive patterns at release 25, is read like any
 * other source.</p>
 *
 * <h2>{@code ExportIndex} JSON (readExports/writeExports)</h2>
 *
 * <pre>{@code
 * {
 *   "schemaVersion": <number, currently 2>,
 *   "toolVersion": "<string>",
 *   "compilationId": "<string>",
 *   "model": {
 *     "graphs": [
 *       {
 *         "facade": "<string>", "packageName": "<string, empty for the unnamed package>",
 *         "entityType": "<string>", "stateType": "<string>", "states": ["<string>", ...],
 *         "transitionType": "<string>", "transitions": ["<string>", ...], "peers": ["<string>", ...],
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
 *   "schemaVersion": <number, currently 2>,
 *   "toolVersion": "<string>",
 *   "local": { ...an ExportIndex, as above... },
 *   "dependencies": [ { ...an ExportIndex, as above... }, ... ],
 *   "processorInventory": "<string, absolute execution path of the processor's inventory>",
 *   "ownedSources": ["<string, path of a .java source relative to its source root>", ...]
 * }
 * }</pre>
 *
 * <h2>{@code OwnedOutputs} JSON (readOwned/writeOwned)</h2>
 *
 * <pre>{@code
 * {
 *   "schemaVersion": <number, currently 2>,
 *   "sources": ["<string, path relative to processorSourcesOutput>", ...],
 *   "binaries": ["<string, path relative to classesOutput>", ...],
 *   "resources": ["<string, path relative to classesOutput>", ...],
 *   "excludedBinaries": ["<string, path relative to classesOutput>", ...]
 * }
 * }</pre>
 *
 * <p>The pending inventory, written before javac runs, also lists among its {@code sources} the sources the
 * generator wrote, relative to {@code sourcesOutput}, as {@link OwnedOutputs} explains.</p>
 *
 * <p>Unlike {@link CompileManifest#ownedSources()}, the four inventory lists are not validated against their
 * roots here: an inventory is read to decide what a build may delete, and that decision belongs to
 * {@link io.github.graphmaquina.codegen.output.CompilationOutputs}, which knows each root and reports a path that
 * escapes one as {@code SG603} instead of accepting a file it does not own.
 *
 * <h2>Processor inventory JSON (readProcessorInventory/writeProcessorInventory)</h2>
 *
 * <pre>{@code
 * {
 *   "schemaVersion": <number, currently 2>,
 *   "generatedTypes": ["<string, qualified name of a source the processor generated>", ...]
 * }
 * }</pre>
 */
public final class ManifestCodec {

    private ManifestCodec() {
    }

    /**
     * Reads a {@link GenerationRequest} from the flat JSON object at {@code path}; one that names no release or
     * no encoding is read at release {@value GenerationRequest#DEFAULT_RELEASE} or in UTF-8.
     *
     * @throws CodegenException {@code SG602} if a member is missing or malformed, the release included, or the
     *     encoding names no charset of the running JVM
     * @throws IllegalArgumentException if a path member is not a valid path, or if {@link GenerationRequest}
     *     refuses the request: a blank {@code compilationId}, or a source that overlaps an output
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
        int release = JsonSupport.optionalInt(object, "release", path).orElse(GenerationRequest.DEFAULT_RELEASE);
        Charset encoding = JsonSupport.optionalString(object, "encoding", path)
            .map((String name) -> charset(name, path))
            .orElse(GenerationRequest.DEFAULT_ENCODING);
        return new GenerationRequest(projectDirectory, sources, classpath, sourcesOutput,
            processorSourcesOutput, classesOutput, workDirectory, compilationId, release, encoding);
    }

    /** The charset {@code name} names, refusing a name the running JVM supports no charset under. */
    private static Charset charset(String name, Path file) {
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException unknown) {
            throw new CodegenException(JsonSupport.ERROR_CODE,
                "member 'encoding' names no charset this JVM supports in " + file + ": " + name);
        }
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
            writer.name("release").value(request.release());
            writer.name("encoding").value(request.encoding().name());
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
        JsonSupport.requireProcessorInventory(processorInventory, path);
        List<String> ownedSources = JsonSupport.requireStringArray(object, "ownedSources", path);
        for (String ownedSource : ownedSources) {
            JsonSupport.requireRelativeInventoryPath(ownedSource, path);
            JsonSupport.requireJavaSource(ownedSource, path);
        }
        return new CompileManifest(schemaVersion, toolVersion, local, dependencies, processorInventory, ownedSources);
    }

    /**
     * Writes {@code manifest} to {@code path}, in the canonical order documented on this class.
     *
     * @throws CodegenException {@code SG602} if {@link #readCompile} would refuse its processor inventory, one that no
     *     path can spell, or an owned source: one that no path can spell, that is absolute or has a root, that holds a
     *     {@code ..} segment, that names the root itself or that is no Java source; nothing is written then
     * @throws IOException if {@code path} cannot be written
     */
    public static void writeCompile(Path path, CompileManifest manifest) throws IOException {
        JsonSupport.requireProcessorInventory(manifest.processorInventory(), path);
        for (String ownedSource : manifest.ownedSources()) {
            JsonSupport.requireRelativeInventoryPath(ownedSource, path);
            JsonSupport.requireJavaSource(ownedSource, path);
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

    /**
     * Reads an {@link OwnedOutputs} inventory from {@code path}, of the current schema only.
     *
     * @throws IOException if {@code path} cannot be read
     */
    public static OwnedOutputs readOwned(Path path) throws IOException {
        JsonObject object = JsonSupport.readObject(path);
        int schemaVersion = JsonSupport.requireInt(object, "schemaVersion", path);
        JsonSupport.requireSupportedSchema(schemaVersion, path);
        return new OwnedOutputs(schemaVersion,
            JsonSupport.requireStringArray(object, "sources", path),
            JsonSupport.requireStringArray(object, "binaries", path),
            JsonSupport.requireStringArray(object, "resources", path),
            JsonSupport.requireStringArray(object, "excludedBinaries", path));
    }

    /**
     * Writes {@code owned} to {@code path}, with every list sorted so an unchanged inventory is unchanged bytes.
     *
     * @throws IOException if {@code path} cannot be written
     */
    public static void writeOwned(Path path, OwnedOutputs owned) throws IOException {
        try (JsonWriter writer = JsonSupport.newWriter(path)) {
            writer.beginObject();
            writer.name("schemaVersion").value(owned.schemaVersion());
            writer.name("sources");
            JsonSupport.writeStringArray(writer, JsonSupport.sortedCopy(owned.sources()));
            writer.name("binaries");
            JsonSupport.writeStringArray(writer, JsonSupport.sortedCopy(owned.binaries()));
            writer.name("resources");
            JsonSupport.writeStringArray(writer, JsonSupport.sortedCopy(owned.resources()));
            writer.name("excludedBinaries");
            JsonSupport.writeStringArray(writer, JsonSupport.sortedCopy(owned.excludedBinaries()));
            writer.endObject();
        }
    }

    /**
     * Reads the qualified names the annotation processor recorded in the inventory at {@code path}, sorted.
     *
     * <p>The processor writes that file itself, with {@code java.nio} and at the absolute path
     * {@link CompileManifest#processorInventory()} names, because the {@code Filer} cannot address the build's
     * work directory; reading it stays here, so this class remains the only place that knows the format.</p>
     *
     * @throws IOException if {@code path} cannot be read
     */
    public static List<String> readProcessorInventory(Path path) throws IOException {
        JsonObject object = JsonSupport.readObject(path);
        JsonSupport.requireSupportedSchema(JsonSupport.requireInt(object, "schemaVersion", path), path);
        return JsonSupport.sortedCopy(JsonSupport.requireStringArray(object, "generatedTypes", path));
    }

    /**
     * Writes the qualified names of every source the annotation processor generated, sorted, to {@code path}.
     *
     * <p>The processor calls this instead of encoding the document itself, so that this class stays the only
     * place that knows the format. The document ends with a newline, which the manifests above do not.</p>
     *
     * @throws IOException if {@code path} cannot be written
     */
    public static void writeProcessorInventory(Path path, List<String> generatedTypes) throws IOException {
        StringWriter document = new StringWriter();
        try (JsonWriter writer = JsonSupport.newWriter(document)) {
            writer.beginObject();
            writer.name("schemaVersion").value(JsonSupport.SCHEMA_VERSION);
            writer.name("generatedTypes");
            JsonSupport.writeStringArray(writer, JsonSupport.sortedCopy(generatedTypes));
            writer.endObject();
        }
        Files.writeString(path, document.toString() + "\n", StandardCharsets.UTF_8);
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
