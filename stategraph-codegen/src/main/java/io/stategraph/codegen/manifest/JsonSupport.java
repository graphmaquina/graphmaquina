package io.stategraph.codegen.manifest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import io.stategraph.codegen.CodegenException;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Strict JSON tree plumbing shared by {@link ManifestCodec} and {@link GenerationModelJson}: reading a document
 * into a {@link JsonObject} tree while rejecting duplicate keys, and typed, null-checked member access that
 * reports every violation as a {@link CodegenException} with code {@value #ERROR_CODE}.
 *
 * <p>Package-private: {@link ManifestCodec} is the only public JSON boundary of this package.
 */
final class JsonSupport {

    static final String ERROR_CODE = "SG602";
    static final int SCHEMA_VERSION = 1;

    private JsonSupport() {
    }

    /** Opens a UTF-8, LF-terminated, two-space-indented writer for {@code path}. */
    static JsonWriter newWriter(Path path) throws IOException {
        return newWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
    }

    /** The same writer over {@code out}, for a document that is assembled before it is written. */
    static JsonWriter newWriter(Writer out) {
        JsonWriter writer = new JsonWriter(out);
        writer.setStrictness(Strictness.STRICT);
        writer.setIndent("  ");
        return writer;
    }

    /** Reads {@code path} as a single strict JSON object, rejecting duplicate keys anywhere in the document. */
    static JsonObject readObject(Path path) throws IOException {
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement element = parseStrict(reader, path);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new CodegenException(ERROR_CODE, "unexpected trailing content in " + path);
            }
            return asObject(element, path);
        }
    }

    private static JsonElement parseStrict(JsonReader reader, Path file) throws IOException {
        JsonToken token = reader.peek();
        return switch (token) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) {
                        throw new CodegenException(ERROR_CODE, "duplicate key '" + name + "' in " + file);
                    }
                    object.add(name, parseStrict(reader, file));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    array.add(parseStrict(reader, file));
                }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new CodegenException(ERROR_CODE, "unexpected token " + token + " in " + file);
        };
    }

    private static JsonElement requireMember(JsonObject object, String name, Path file) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new CodegenException(ERROR_CODE, "missing member '" + name + "' in " + file);
        }
        return element;
    }

    static String requireString(JsonObject object, String name, Path file) {
        JsonElement element = requireMember(object, name, file);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new CodegenException(ERROR_CODE, "member '" + name + "' must be a string in " + file);
        }
        return element.getAsString();
    }

    static int requireInt(JsonObject object, String name, Path file) {
        JsonElement element = requireMember(object, name, file);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new CodegenException(ERROR_CODE, "member '" + name + "' must be a number in " + file);
        }
        return element.getAsInt();
    }

    static long requireLong(JsonObject object, String name, Path file) {
        JsonElement element = requireMember(object, name, file);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new CodegenException(ERROR_CODE, "member '" + name + "' must be a number in " + file);
        }
        return element.getAsLong();
    }

    static boolean requireBoolean(JsonObject object, String name, Path file) {
        JsonElement element = requireMember(object, name, file);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new CodegenException(ERROR_CODE, "member '" + name + "' must be a boolean in " + file);
        }
        return element.getAsBoolean();
    }

    static JsonObject requireObject(JsonObject object, String name, Path file) {
        return asObject(requireMember(object, name, file), file);
    }

    static JsonObject asObject(JsonElement element, Path file) {
        if (!element.isJsonObject()) {
            throw new CodegenException(ERROR_CODE, "expected a JSON object in " + file);
        }
        return element.getAsJsonObject();
    }

    static JsonArray requireArray(JsonObject object, String name, Path file) {
        JsonElement element = requireMember(object, name, file);
        if (!element.isJsonArray()) {
            throw new CodegenException(ERROR_CODE, "member '" + name + "' must be an array in " + file);
        }
        return element.getAsJsonArray();
    }

    static List<String> requireStringArray(JsonObject object, String name, Path file) {
        JsonArray array = requireArray(object, name, file);
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new CodegenException(ERROR_CODE, "member '" + name + "' must contain only strings in " + file);
            }
            values.add(element.getAsString());
        }
        return values;
    }

    static JsonObject optionalObject(JsonObject object, String name, Path file) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return asObject(element, file);
    }

    static void requireSupportedSchema(int schemaVersion, Path file) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new CodegenException(ERROR_CODE,
                "unsupported schemaVersion " + schemaVersion + " in " + file + "; expected " + SCHEMA_VERSION);
        }
    }

    /** Rejects an inventory path that is absolute or escapes its root through a {@code ..} segment. */
    static void requireRelativeInventoryPath(String value, Path file) {
        Path candidate = Path.of(value);
        if (candidate.isAbsolute()) {
            throw new CodegenException(ERROR_CODE, "inventory path '" + value + "' must be relative in " + file);
        }
        for (Path segment : candidate) {
            if ("..".equals(segment.toString())) {
                throw new CodegenException(ERROR_CODE, "inventory path '" + value + "' escapes its root in " + file);
            }
        }
    }

    static void writeStringArray(JsonWriter writer, List<String> values) throws IOException {
        writer.beginArray();
        for (String value : values) {
            writer.value(value);
        }
        writer.endArray();
    }

    /** A copy of {@code values} sorted lexicographically, for canonical serialization of identity lists. */
    static List<String> sortedCopy(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(Comparator.naturalOrder());
        return copy;
    }
}
