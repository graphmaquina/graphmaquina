package io.github.graphmaquina.processor.gen;

import java.util.Locale;

/**
 * Minimal indented text builder for generated sources. Lines end with "\n".
 *
 * <p>The source it answers holds printable ASCII and line feeds only: every other character, in a name or in a
 * text, is written as the Unicode escape javac reads in any encoding, one per UTF-16 unit, an unpaired surrogate
 * included. javac's {@code Filer} writes a generated source in the encoding of the compilation and replaces a
 * character that encoding cannot represent with {@code ?}, which would change a text and break a name. A backslash
 * starts such an escape only after an even run of backslashes, and a generated source holds a backslash only in a
 * string literal, written by {@link MachineGenerator#quote} or by javac's own rendering of an annotation value, where
 * backslashes come in pairs or start an escape sequence; so no escape written here follows an odd run.</p>
 */
final class SourceWriter {

    private final StringBuilder text = new StringBuilder();
    private int depth;

    SourceWriter line(String content) {
        if (!content.isEmpty()) {
            text.append("    ".repeat(depth));
        }
        text.append(content).append('\n');
        return this;
    }

    SourceWriter blank() {
        text.append('\n');
        return this;
    }

    /**
     * The package declaration of a file of {@code packageName} and the blank line after it, and nothing for the
     * unnamed package: a compilation unit of the unnamed package declares no package at all.
     */
    SourceWriter packageDeclaration(String packageName) {
        if (!packageName.isEmpty()) {
            line("package " + packageName + ";");
            blank();
        }
        return this;
    }

    SourceWriter open(String content) {
        line(content + " {");
        depth++;
        return this;
    }

    SourceWriter close() {
        depth--;
        return line("}");
    }

    SourceWriter indent() {
        depth++;
        return this;
    }

    SourceWriter outdent() {
        depth--;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char unit = text.charAt(index);
            if (unit == '\n' || (unit >= ' ' && unit <= '~')) {
                escaped.append(unit);
            } else {
                escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) unit));
            }
        }
        return escaped.toString();
    }
}
