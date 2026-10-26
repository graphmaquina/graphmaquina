package io.github.graphmaquina.codegen;

/**
 * The names and the schema of the files that the generator, the annotation processor and the build plugins hand to
 * one another, declared once for the generator and the processor. The build plugins never load the generator's
 * classes, so each repeats the names it writes or reads; their end-to-end tests run the real generator, which a name
 * out of step fails.
 */
public final class GenerationFiles {

    /** The schema of every manifest, export index and inventory this tool writes, and the only one it reads. */
    public static final int SCHEMA_VERSION = 2;

    /** Where a compilation publishes its export index, relative to its class output. */
    public static final String PUBLISHED_INDEX = "META-INF/graphmaquina/exports.json";

    /**
     * Where the processor writes the export index of a compilation still in progress, relative to its class output.
     */
    public static final String PENDING_INDEX = "META-INF/graphmaquina/pending-exports.json";

    /** The compile manifest the processor reads, in the work directory. */
    public static final String MANIFEST = "annotation-manifest.json";

    /** The inventory of the outputs a completed compilation owns, in the work directory. */
    public static final String INVENTORY = "owned-outputs.json";

    /** The inventory a preparation writes before javac runs, in the work directory. */
    public static final String PENDING_INVENTORY = "owned-outputs.pending.json";

    /** The package of the compilation's carrier, whose class files completing the compilation removes. */
    public static final String CARRIER_PACKAGE = "io.github.graphmaquina.generated.bootstrap";

    private GenerationFiles() {
    }
}
