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
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationFiles;
import java.io.EOFException;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * Strict JSON tree plumbing shared by {@link ManifestCodec} and {@link GenerationModelJson}: reading a document
 * into a {@link JsonObject} tree while rejecting duplicate keys, and typed, null-checked member access that
 * reports every violation as a {@link CodegenException} with code SG602. A document that is not
 * well-formed strict JSON, holds bytes that are not UTF-8 text, escapes a surrogate that is not half of a pair in a
 * string or a name, carries content after its end or holds a number whose exponent lies beyond the range a
 * {@link BigDecimal} holds is reported the same way, naming its file.
 *
 * <p>Package-private: {@link ManifestCodec} is the only public JSON boundary of this package.
 */
final class JsonSupport {

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
     * @throws CodegenException SG602 naming {@code path} if the document is not well-formed
     *     strict JSON, for example because a write was interrupted, holds bytes that are not UTF-8 text, as a
     *     write cut off inside a multi-byte character leaves, escapes in a string or a name a surrogate that is not
     *     half of a pair, which no UTF-8 file can hold, carries content after its end, or holds a number,
     *     wherever it stands, whose exponent lies beyond the range a {@link BigDecimal} holds, such as
     *     {@code 1e99999999999}
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
                throw new CodegenException(DiagnosticCode.SG602.name(),
                        "malformed JSON in " + path + ": " + describe(malformed));
            } catch (CharacterCodingException undecodable) {
                // The reader decodes a whole buffer ahead of the token it reads, so no position would be exact.
                throw new CodegenException(DiagnosticCode.SG602.name(),
                        "malformed JSON in " + path + ": bytes that are not UTF-8 text");
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
            throw new CodegenException(DiagnosticCode.SG602.name(), "unexpected trailing content in " + file);
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
                    String name = paired(reader.nextName(), reader::getPath, file);
                    if (object.has(name)) {
                        throw new CodegenException(DiagnosticCode.SG602.name(),
                                "duplicate key '" + name + "' in " + file);
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
            case STRING -> new JsonPrimitive(paired(reader.nextString(), reader::getPreviousPath, file));
            case NUMBER -> {
                String text = reader.nextString();
                try {
                    yield new JsonPrimitive(new BigDecimal(text));
                } catch (NumberFormatException outOfRange) {
                    // Well-formed JSON all the same, such as 1e99999999999: no BigDecimal holds that exponent.
                    throw new CodegenException(DiagnosticCode.SG602.name(),
                        "number with an exponent out of range in " + file + ": "
                        + text + " at path " + reader.getPreviousPath());
                }
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new CodegenException(DiagnosticCode.SG602.name(),
                    "unexpected token " + token + " in " + file);
        };
    }

    /**
     * {@code text}, a string or a name read at the place {@code path} describes, refused when it holds a surrogate
     * that is not half of a pair: strict JSON can escape one, but no UTF-8 file can hold it, so the tools could not
     * write it back. The place is named with such a surrogate escaped, as the message itself must be text. It is
     * asked for only then, before the reader moves on, since the reader builds a new text each time it describes one.
     */
    private static String paired(String text, Supplier<String> path, Path file) {
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (Character.charCount(codePoint) == 1 && Character.isSurrogate((char) codePoint)) {
                throw new CodegenException(DiagnosticCode.SG602.name(),
                    "malformed JSON in " + file + ": an unpaired surrogate "
                    + escaped(Character.toString(codePoint)) + " at path " + escaped(path.get()));
            }
            index += Character.charCount(codePoint);
        }
        return text;
    }

    /** {@code text} with every surrogate that is not half of a pair written as its Unicode escape. */
    private static String escaped(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        text.codePoints().forEach((int codePoint) -> escaped.append(
                Character.charCount(codePoint) == 1 && Character.isSurrogate((char) codePoint)
                        ? String.format(Locale.ROOT, "\\u%04x", codePoint)
                        : Character.toString(codePoint)));
        return escaped.toString();
    }

    private static JsonElement requireMember(JsonObject object, String name, Path file) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new CodegenException(DiagnosticCode.SG602.name(), "missing member '" + name + "' in " + file);
        }
        return element;
    }

    static String requireString(JsonObject object, String name, Path file) {
        JsonElement element = requireMember(object, name, file);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                    "member '" + name + "' must be a string in " + file);
        }
        return element.getAsString();
    }

    /** The member as an {@code int}, refusing a fraction or a value outside that range instead of truncating it. */
    static int requireInt(JsonObject object, String name, Path file) {
        BigDecimal value = requireNumber(object, name, file);
        try {
            return value.intValueExact();
        } catch (ArithmeticException inexact) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                "member '" + name + "' must be a 32-bit integer in " + file + ", but is " + value);
        }
    }

    /** The member as a {@code long}, refusing a fraction or a value outside that range instead of truncating it. */
    static long requireLong(JsonObject object, String name, Path file) {
        BigDecimal value = requireNumber(object, name, file);
        try {
            return value.longValueExact();
        } catch (ArithmeticException inexact) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                "member '" + name + "' must be a 64-bit integer in " + file + ", but is " + value);
        }
    }

    private static BigDecimal requireNumber(JsonObject object, String name, Path file) {
        JsonElement element = requireMember(object, name, file);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                    "member '" + name + "' must be a number in " + file);
        }
        return element.getAsBigDecimal();
    }

    static boolean requireBoolean(JsonObject object, String name, Path file) {
        JsonElement element = requireMember(object, name, file);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                    "member '" + name + "' must be a boolean in " + file);
        }
        return element.getAsBoolean();
    }

    static JsonObject requireObject(JsonObject object, String name, Path file) {
        return asObject(requireMember(object, name, file), file);
    }

    static JsonObject asObject(JsonElement element, Path file) {
        if (!element.isJsonObject()) {
            throw new CodegenException(DiagnosticCode.SG602.name(), "expected a JSON object in " + file);
        }
        return element.getAsJsonObject();
    }

    static JsonArray requireArray(JsonObject object, String name, Path file) {
        JsonElement element = requireMember(object, name, file);
        if (!element.isJsonArray()) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                    "member '" + name + "' must be an array in " + file);
        }
        return element.getAsJsonArray();
    }

    static List<String> requireStringArray(JsonObject object, String name, Path file) {
        JsonArray array = requireArray(object, name, file);
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new CodegenException(DiagnosticCode.SG602.name(),
                        "member '" + name + "' must contain only strings in " + file);
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
     * Accepts only the current schema, including inventories. A document of any other schema is rejected before
     * its model or owned paths can be used.
     */
    static void requireSupportedSchema(int schemaVersion, Path file) {
        if (schemaVersion != GenerationFiles.SCHEMA_VERSION) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                "unsupported schemaVersion " + schemaVersion + " in " + file + "; expected "
                    + GenerationFiles.SCHEMA_VERSION);
        }
    }

    /**
     * Rejects a processor inventory that no path can spell, such as one holding the NUL character: every step that
     * reads or writes that inventory makes a path of it.
     */
    static void requireProcessorInventory(String value, Path file) {
        try {
            Path.of(value);
        } catch (InvalidPathException unspellable) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                    "processor inventory '" + value + "' is not a path in " + file);
        }
    }

    /**
     * Rejects an inventory path that no path can spell, such as one holding the NUL character, one that is absolute
     * or has a root, as {@code \x} and {@code C:x} have on Windows, where resolving one against a directory can give
     * a path outside that directory, one that holds a {@code ..} segment, whether or not that segment leads out of its
     * root, and one that names its root itself, as {@code .} and the empty path do.
     */
    static void requireRelativeInventoryPath(String value, Path file) {
        Path candidate;
        try {
            candidate = Path.of(value);
        } catch (InvalidPathException unspellable) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                    "inventory path '" + value + "' is not a path in " + file);
        }
        if (candidate.isAbsolute() || candidate.getRoot() != null) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                    "inventory path '" + value + "' must be relative in " + file);
        }
        for (Path segment : candidate) {
            if ("..".equals(segment.toString())) {
                throw new CodegenException(DiagnosticCode.SG602.name(),
                        "inventory path '" + value + "' escapes its root in " + file);
            }
        }
        if (candidate.normalize().toString().isEmpty()) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                    "inventory path '" + value + "' names no file in " + file);
        }
    }

    /**
     * Rejects an owned source whose file name is not a name followed by {@code .java}: every source a generation
     * owns is a Java source, and the type it declares is read from its path.
     */
    static void requireJavaSource(String value, Path file) {
        String fileName = value.substring(value.lastIndexOf('/') + 1);
        if (!fileName.endsWith(".java") || fileName.length() == ".java".length()) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                    "inventory path '" + value + "' names no Java source in " + file);
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
