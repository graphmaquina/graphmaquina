package io.github.graphmaquina.test.snapshot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The line format of a snapshot: a version line, then one {@code path TAB value} line per field, each
 * terminated by a line feed. Backslash, tab, line feed and carriage return are escaped in both halves, so
 * the separator and the line break are never ambiguous and a value may be empty or carry any text at all.
 *
 * <p>Only {@code graphmaquina-snapshot-v1} is read; a document with any other version line is refused.
 */
final class SnapshotCodec {

    private static final String VERSION = "graphmaquina-snapshot-v1";

    private SnapshotCodec() { }

    /** Writes the fields in the iteration order of the map, which {@link GraphSnapshot} keeps canonical. */
    static String encode(Map<String, String> fields) {
        StringBuilder text = new StringBuilder(VERSION).append('\n');
        for (Map.Entry<String, String> field : fields.entrySet()) {
            text.append(escape(field.getKey())).append('\t').append(escape(field.getValue())).append('\n');
        }
        return text.toString();
    }

    static Map<String, String> decode(String text) {
        String[] lines = text.split("\n", -1);
        if (!VERSION.equals(lines[0])) {
            throw new IllegalArgumentException("unsupported snapshot version \"" + escape(lines[0])
                    + "\", expected \"" + VERSION + "\"");
        }
        if (!lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("the snapshot must end with a line feed");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int number = 1; number < lines.length - 1; number++) {
            decodeLine(fields, lines[number], number + 1);
        }
        return fields;
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void decodeLine(Map<String, String> fields, String line, int number) {
        StringBuilder current = new StringBuilder();
        String path = null;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (escaped) {
                current.append(unescape(character, number));
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '\t') {
                if (path != null) {
                    throw new IllegalArgumentException("line " + number
                            + " has more than one unescaped separator");
                }
                path = current.toString();
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (escaped) {
            throw new IllegalArgumentException("line " + number + " ends with a truncated escape");
        }
        if (path == null) {
            throw new IllegalArgumentException("line " + number + " has no separator");
        }
        if (fields.put(path, current.toString()) != null) {
            throw new IllegalArgumentException("the path <" + path + "> is repeated on line " + number);
        }
    }

    private static char unescape(char character, int number) {
        return switch (character) {
            case '\\' -> '\\';
            case 't' -> '\t';
            case 'n' -> '\n';
            case 'r' -> '\r';
            default -> throw new IllegalArgumentException("line " + number
                    + " carries the unknown escape <\\" + character + ">");
        };
    }
}
