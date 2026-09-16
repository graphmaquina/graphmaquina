package io.github.graphmaquina.codegen.manifest;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.PropertyDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Converts {@link GenerationModel} and its parts to and from the JSON shape documented on {@link ManifestCodec}.
 * Identity lists (graphs, symbols, a symbol's candidates, a symbol's kinds) are sorted by name before writing;
 * a graph's declared states, its declared transition identifiers and a member's parameter types keep their
 * original, semantically ordered sequence.
 *
 * <p>Package-private: {@link ManifestCodec} is the only public JSON boundary of this package.
 */
final class GenerationModelJson {

    private GenerationModelJson() {
    }

    static void write(JsonWriter writer, GenerationModel model) throws IOException {
        writer.beginObject();
        writer.name("graphs");
        writer.beginArray();
        for (GraphDescriptor graph : model.graphs().stream()
                .sorted(Comparator.comparing(GraphDescriptor::facade)).toList()) {
            writeGraph(writer, graph);
        }
        writer.endArray();
        writer.name("symbols");
        writer.beginArray();
        for (SymbolDescriptor symbol : model.symbols().stream()
                .sorted(Comparator.comparing(SymbolDescriptor::qualifiedName)).toList()) {
            writeSymbol(writer, symbol);
        }
        writer.endArray();
        writer.endObject();
    }

    static GenerationModel read(JsonObject object, Path file) {
        List<GraphDescriptor> graphs = JsonSupport.requireArray(object, "graphs", file).asList().stream()
            .map(element -> readGraph(JsonSupport.asObject(element, file), file)).toList();
        List<SymbolDescriptor> symbols = JsonSupport.requireArray(object, "symbols", file).asList().stream()
            .map(element -> readSymbol(JsonSupport.asObject(element, file), file)).toList();
        return new GenerationModel(graphs, symbols);
    }

    private static void writeOrigin(JsonWriter writer, SourceLocation origin) throws IOException {
        writer.beginObject();
        writer.name("path").value(origin.path());
        writer.name("line").value(origin.line());
        writer.name("column").value(origin.column());
        writer.endObject();
    }

    private static SourceLocation readOrigin(JsonObject object, Path file) {
        String path = JsonSupport.requireString(object, "path", file);
        long line = JsonSupport.requireLong(object, "line", file);
        long column = JsonSupport.requireLong(object, "column", file);
        return new SourceLocation(path, line, column);
    }

    private static void writeGraph(JsonWriter writer, GraphDescriptor graph) throws IOException {
        writer.beginObject();
        writer.name("facade").value(graph.facade());
        writer.name("packageName").value(graph.packageName());
        writer.name("entityType").value(graph.entityType());
        writer.name("stateType").value(graph.stateType());
        writer.name("states");
        JsonSupport.writeStringArray(writer, graph.states());
        writer.name("transitionType").value(graph.transitionType());
        writer.name("transitions");
        JsonSupport.writeStringArray(writer, graph.transitions());
        writer.name("peers");
        JsonSupport.writeStringArray(writer, JsonSupport.sortedCopy(graph.peers()));
        writer.name("origin");
        writeOrigin(writer, graph.origin());
        writer.endObject();
    }

    private static GraphDescriptor readGraph(JsonObject object, Path file) {
        String facade = JsonSupport.requireString(object, "facade", file);
        String packageName = JsonSupport.requireString(object, "packageName", file);
        String entityType = JsonSupport.requireString(object, "entityType", file);
        String stateType = JsonSupport.requireString(object, "stateType", file);
        List<String> states = JsonSupport.requireStringArray(object, "states", file);
        String transitionType = JsonSupport.requireString(object, "transitionType", file);
        List<String> transitions = JsonSupport.requireStringArray(object, "transitions", file);
        List<String> peers = JsonSupport.requireStringArray(object, "peers", file);
        SourceLocation origin = readOrigin(JsonSupport.requireObject(object, "origin", file), file);
        return new GraphDescriptor(facade, packageName, entityType, stateType, states, transitionType,
            transitions, peers, origin);
    }

    private static void writeMember(JsonWriter writer, MemberDescriptor member) throws IOException {
        writer.beginObject();
        writer.name("declaringType").value(member.declaringType());
        writer.name("name").value(member.name());
        writer.name("parameterTypes");
        JsonSupport.writeStringArray(writer, member.parameterTypes());
        writer.name("returnType").value(member.returnType());
        writer.name("role").value(member.role().name());
        writer.name("staticMember").value(member.staticMember());
        writer.name("origin");
        writeOrigin(writer, member.origin());
        writer.endObject();
    }

    private static MemberDescriptor readMember(JsonObject object, Path file) {
        String declaringType = JsonSupport.requireString(object, "declaringType", file);
        String name = JsonSupport.requireString(object, "name", file);
        List<String> parameterTypes = JsonSupport.requireStringArray(object, "parameterTypes", file);
        String returnType = JsonSupport.requireString(object, "returnType", file);
        SymbolKind role = parseSymbolKind(JsonSupport.requireString(object, "role", file), file);
        boolean staticMember = JsonSupport.requireBoolean(object, "staticMember", file);
        SourceLocation origin = readOrigin(JsonSupport.requireObject(object, "origin", file), file);
        return new MemberDescriptor(declaringType, name, parameterTypes, returnType, role, staticMember, origin);
    }

    private static void writeProperty(JsonWriter writer, PropertyDescriptor property) throws IOException {
        writer.beginObject();
        writer.name("declaringType").value(property.declaringType());
        writer.name("logicalName").value(property.logicalName());
        writer.name("declarationName").value(property.declarationName());
        writer.name("directGetter").value(property.directGetter());
        writer.name("origin");
        writeOrigin(writer, property.origin());
        writer.endObject();
    }

    private static PropertyDescriptor readProperty(JsonObject object, Path file) {
        String declaringType = JsonSupport.requireString(object, "declaringType", file);
        String logicalName = JsonSupport.requireString(object, "logicalName", file);
        String declarationName = JsonSupport.requireString(object, "declarationName", file);
        boolean directGetter = JsonSupport.requireBoolean(object, "directGetter", file);
        SourceLocation origin = readOrigin(JsonSupport.requireObject(object, "origin", file), file);
        return new PropertyDescriptor(declaringType, logicalName, declarationName, directGetter, origin);
    }

    private static void writeSymbol(JsonWriter writer, SymbolDescriptor symbol) throws IOException {
        writer.beginObject();
        writer.name("qualifiedName").value(symbol.qualifiedName());
        writer.name("host").value(symbol.host());
        writer.name("member").value(symbol.member());
        writer.name("kinds");
        writer.beginArray();
        for (SymbolKind kind : sortedKinds(symbol.kinds())) {
            writer.value(kind.name());
        }
        writer.endArray();
        writer.name("candidates");
        writer.beginArray();
        for (MemberDescriptor candidate : symbol.candidates().stream()
                .sorted(Comparator.comparing(MemberDescriptor::name)
                    .thenComparing(member -> String.join(",", member.parameterTypes())))
                .toList()) {
            writeMember(writer, candidate);
        }
        writer.endArray();
        if (symbol.property().isPresent()) {
            writer.name("property");
            writeProperty(writer, symbol.property().get());
        }
        writer.name("origin");
        writeOrigin(writer, symbol.origin());
        writer.endObject();
    }

    private static SymbolDescriptor readSymbol(JsonObject object, Path file) {
        String qualifiedName = JsonSupport.requireString(object, "qualifiedName", file);
        String host = JsonSupport.requireString(object, "host", file);
        String member = JsonSupport.requireString(object, "member", file);
        Set<SymbolKind> kinds = EnumSet.noneOf(SymbolKind.class);
        for (String kind : JsonSupport.requireStringArray(object, "kinds", file)) {
            kinds.add(parseSymbolKind(kind, file));
        }
        List<MemberDescriptor> candidates = JsonSupport.requireArray(object, "candidates", file).asList().stream()
            .map(element -> readMember(JsonSupport.asObject(element, file), file)).toList();
        JsonObject propertyObject = JsonSupport.optionalObject(object, "property", file);
        Optional<PropertyDescriptor> property = propertyObject == null
            ? Optional.empty()
            : Optional.of(readProperty(propertyObject, file));
        SourceLocation origin = readOrigin(JsonSupport.requireObject(object, "origin", file), file);
        return new SymbolDescriptor(qualifiedName, host, member, kinds, candidates, property, origin);
    }

    private static SymbolKind parseSymbolKind(String value, Path file) {
        try {
            return SymbolKind.valueOf(value);
        } catch (IllegalArgumentException cause) {
            throw new CodegenException(JsonSupport.ERROR_CODE, "unknown symbol kind '" + value + "' in " + file);
        }
    }

    private static List<SymbolKind> sortedKinds(Set<SymbolKind> kinds) {
        return kinds.stream().sorted(Comparator.comparing(SymbolKind::name)).toList();
    }
}
