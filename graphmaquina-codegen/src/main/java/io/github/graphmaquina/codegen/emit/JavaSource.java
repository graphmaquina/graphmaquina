package io.github.graphmaquina.codegen.emit;

import java.util.Collection;
import java.util.List;

/**
 * Accumulates one generated compilation unit as indented text with LF line endings, and renders the parts every
 * emitted file shares: its package declaration, its single-type imports and its documentation comments.
 *
 * <p>Nothing here interprets Java: a caller writes the exact text of each line and this class only owns the
 * indentation and the line separator, so two runs over the same model always produce the same bytes.</p>
 */
final class JavaSource {

    private static final String INDENT = "    ";

    private final StringBuilder text = new StringBuilder();
    private int depth;

    /** Appends {@code content} at the current depth, or an empty line, which carries no indentation. */
    void line(String content) {
        if (!content.isEmpty()) {
            text.append(INDENT.repeat(depth)).append(content);
        }
        text.append('\n');
    }

    /** Opens one level of indentation for the lines that follow. */
    void indent() {
        depth++;
    }

    /** Closes one level of indentation. */
    void outdent() {
        if (depth == 0) {
            throw new IllegalStateException("the generated source is already at its outermost level");
        }
        depth--;
    }

    /**
     * Writes the package declaration of {@code packageName}, empty for the unnamed package, and one single-type
     * import per entry of {@code imports}, in the order given, followed by a blank line.
     */
    void header(String packageName, Collection<String> imports) {
        if (!packageName.isEmpty()) {
            line("package " + packageName + ";");
            line("");
        }
        if (!imports.isEmpty()) {
            for (String type : imports) {
                line("import " + type + ";");
            }
            line("");
        }
    }

    /** Writes a documentation comment holding {@code lines} at the current depth, without trailing spaces. */
    void javadoc(String... lines) {
        line("/**");
        for (String content : List.of(lines)) {
            line(content.isEmpty() ? " *" : " * " + content);
        }
        line(" */");
    }

    /** The compilation unit written so far. */
    @Override
    public String toString() {
        return text.toString();
    }
}
