package io.github.graphmaquina.codegen.manifest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;
import io.github.graphmaquina.codegen.CodegenException;
import java.io.EOFException;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Strict JSON tree plumbing shared by {@link ManifestCodec} and {@link GenerationModelJson}: reading a document
 * into a {@link JsonObject} tree while rejecting duplicate keys, and typed, null-checked member access that
 * reports every violation as a {@link CodegenException} with code {@value #ERROR_CODE}. A document that is not
 * well-formed strict JSON, holds bytes that are not UTF-8 text or carries content after its end is reported the
 * same way, naming its file.
 *
 * <p>Package-private: {@link ManifestCodec} is the only public JSON boundary of this package.
 */
final class JsonSupport {

    static final String ERROR_CODE = "SG602";
    static final int SCHEMA_VERSION = 2;
    static final int RETIRED_SCHEMA_VERSION = 1;
    /** What a strict reader says about syntax only its lenient mode accepts. */
    private static final String LENIENT_ADVICE =
        "Use JsonReader.setStrictness(Strictness.LENIENT) to accept malformed JSON";

    private JsonSupport() {
    }

    /**
     * Opens a strict, UTF-8, two-space-indented writer for {@code path}, which breaks lines with LF and adds none
     * after the document.
     */
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

    /**
     * Reads {@code path} as a single strict JSON object, rejecting duplicate keys anywhere in the document.
     *
     * @throws CodegenException {@value #ERROR_CODE} naming {@code path} if the document is not well-formed
     *     strict JSON, for example because a write was interrupted, holds bytes that are not UTF-8 text, as a
     *     write cut off inside a multi-byte character leaves, or carries content after its end
     * @throws IOException if {@code path} cannot be opened or read
     */
    static JsonObject readObject(Path path) throws IOException {
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement element;
            try {
                element = parseStrict(reader, path);
                requireEndOfDocument(reader, path);
            } catch (MalformedJsonException | EOFException malformed) {
                throw new CodegenException(ERROR_CODE, "malformed JSON in " + path + ": " + describe(malformed));
            } catch (CharacterCodingException undecodable) {
                // The reader decodes a whole buffer ahead of the token it reads, so no position would be exact.
                throw new CodegenException(ERROR_CODE, "malformed JSON in " + path + ": bytes that are not UTF-8 text");
            }
            return asObject(element, path);
        }
    }

    /**
     * Refuses anything but whitespace after the document. A strict reader reports such content as a syntax
     * error of its own rather than as a token, so both are read here as the same trailing content.
     */
    private static void requireEndOfDocument(JsonReader reader, Path file) throws IOException {
        boolean ended;
        try {
            ended = reader.peek() == JsonToken.END_DOCUMENT;
        } catch (MalformedJsonException trailing) {
            ended = false;
        }
        if (!ended) {
            throw new CodegenException(ERROR_CODE, "unexpected trailing content in " + file);
        }
    }

    /**
     * The reader's own description of a syntax error, which ends with its position: only its first line, since
     * the next one links a troubleshooting guide, and without the advice to accept the document leniently,
     * which nobody reading these files can follow.
     */
    private static String describe(IOException malformed) {
        String message = String.valueOf(malformed.getMessage());
        int lineBreak = message.indexOf('\n');
        String firstLine = lineBreak < 0 ? message : message.substring(0, lineBreak);
        return firstLine.replace(LENIENT_ADVICE, "content that strict JSON does not allow");
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

    /** The member as an {@code int}, refusing a fraction or a value outside that range instead of truncating it. */
    static int requireInt(JsonObject object, String name, Path file) {
        BigDecimal value = requireNumber(object, name, file);
        try {
            return value.intValueExact();
        } catch (ArithmeticException inexact) {
            throw new CodegenException(ERROR_CODE,
                "member '" + name + "' must be a 32-bit integer in " + file + ", but is " + value);
        }
    }

    /** The member as a {@code long}, refusing a fraction or a value outside that range instead of truncating it. */
    static long requireLong(JsonObject object, String name, Path file) {
        BigDecimal value = requireNumber(object, name, file);
        try {
            return value.longValueExact();
        } catch (ArithmeticException inexact) {
            throw new CodegenException(ERROR_CODE,
                "member '" + name + "' must be a 64-bit integer in " + file + ", but is " + value);
        }
    }

    private static BigDecimal requireNumber(JsonObject object, String name, Path file) {
        JsonElement element = requireMember(object, name, file);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new CodegenException(ERROR_CODE, "member '" + name + "' must be a number in " + file);
        }
        return element.getAsBigDecimal();
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

    /** The member as {@link #requireInt} reads it, or empty when the member is absent or {@code null}. */
    static OptionalInt optionalInt(JsonObject object, String name, Path file) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull()
                ? OptionalInt.empty()
                : OptionalInt.of(requireInt(object, name, file));
    }

    /** The member as {@link #requireString} reads it, or empty when the member is absent or {@code null}. */
    static Optional<String> optionalString(JsonObject object, String name, Path file) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull()
                ? Optional.empty()
                : Optional.of(requireString(object, name, file));
    }

    static JsonObject optionalObject(JsonObject object, String name, Path file) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return asObject(element, file);
    }

    /**
     * Accepts only the current schema, including inventories. A retired document is rejected before its
     * model or owned paths can be used; cleaning an old build belongs to the user.
     */
    static void requireSupportedSchema(int schemaVersion, Path file) {
        if (schemaVersion == RETIRED_SCHEMA_VERSION) {
            throw new CodegenException(ERROR_CODE, "the schema 1 of " + file
                + " is the retired format of GraphMaquina 1.x; run clean, then regenerate and recompile the producer and the"
                + " consumer with 2.0, which reads schema 2 only and converts nothing");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new CodegenException(ERROR_CODE,
                "unsupported schemaVersion " + schemaVersion + " in " + file + "; expected " + SCHEMA_VERSION);
        }
    }

    /**
     * Rejects an inventory path that is absolute or holds a {@code ..} segment, whether or not that segment leads
     * out of its root.
     */
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
