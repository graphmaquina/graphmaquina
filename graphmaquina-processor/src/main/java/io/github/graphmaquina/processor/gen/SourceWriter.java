package io.github.graphmaquina.processor.gen;

import java.util.List;
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

    /**
     * The longest text of a documentation comment written on one line; a longer one, or one with block tags, takes
     * the multi-line form, wrapped at this width. The bound stays below what one emitted line can hold within 120
     * columns at the indentation of a member of a nested class.
     */
    private static final int DOC_LINE_WIDTH = 100;

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

    /**
     * {@code doc} as a documentation comment, then each of {@code tags}, a block tag such as {@code @param} or
     * {@code @return}, after a blank comment line: on one line when there is no tag and the text is at most
     * {@code DOC_LINE_WIDTH} characters, else in the multi-line form, each text wrapped at that width. Every public
     * member of a generated file carries one, with the tags its signature calls for: the author cannot comment a
     * generated file, and a {@code javadoc} run warns about a member without them.
     */
    SourceWriter javadoc(String doc, List<String> tags) {
        if (tags.isEmpty() && doc.length() <= DOC_LINE_WIDTH) {
            return line("/** " + doc + " */");
        }
        line("/**");
        wrap(doc);
        if (!tags.isEmpty()) {
            line(" *");
            for (String tag : tags) {
                wrap(tag);
            }
        }
        return line(" */");
    }

    /** Writes {@code doc} as lines of a documentation comment, breaking it between words at {@code DOC_LINE_WIDTH}. */
    private void wrap(String doc) {
        StringBuilder current = new StringBuilder();
        for (String word : doc.split(" ")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > DOC_LINE_WIDTH) {
                line(" * " + current);
                current.setLength(0);
            }
            current.append(current.length() == 0 ? "" : " ").append(word);
        }
        line(" * " + current);
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
