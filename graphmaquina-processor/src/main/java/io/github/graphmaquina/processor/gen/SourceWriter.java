package io.github.graphmaquina.processor.gen;

/** Minimal indented text builder for generated sources. Lines end with "\n". */
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
        return text.toString();
    }
}
