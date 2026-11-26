package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Which accessors the conventions find on an entity whose members it declares and inherits, pinned whole: the
 * companion of the set that reads and writes the entity's state with the reader and the writer they pick, byte for
 * byte, as {@link PinnedCompanions} keeps it, and the writers reported when two of one alternative tie, in the order
 * the entity has them.
 */
class AccessorConventionsTest {

    /**
     * The base the entity inherits a conventional reader and two writers of {@code setStatus} from, the one taking
     * the states being the more specific.
     */
    private static final String BASE = """
            package fx;
            public class DocBase {
                public DocStatus getStatus() { return DocStatus.DRAFT; }
                public void setStatus(Object value) { }
                public void setStatus(DocStatus value) { }
            }
            """;

    /**
     * The entity: its {@code status()} returns no state, so the inherited {@code getStatus()} reads it before its own
     * {@code isStatus()}; and the inherited {@code setStatus} alternative writes it before its own {@code status(x)}.
     */
    private static final String DOC = """
            package fx;
            import io.github.graphmaquina.annotation.GraphProperty;
            public final class Doc extends DocBase {
                @GraphProperty private DocStatus status = DocStatus.DRAFT;
                @GraphProperty public String id() { return "doc"; }
                public String status() { return status.name(); }
                public DocStatus isStatus() { return status; }
                public void status(DocStatus value) { status = value; }
            }
            """;

    /** An entity whose one writer alternative is split between it and its base, neither writer more specific. */
    private static final String TIED_DOC = """
            package fx;
            import io.github.graphmaquina.annotation.GraphProperty;
            public final class Doc extends DocBase {
                @GraphProperty private DocStatus status = DocStatus.DRAFT;
                @GraphProperty public String id() { return "doc"; }
                public DocStatus status() { return status; }
                public void status(Comparable<DocStatus> value) { }
            }
            """;

    private static final String TIED_BASE = """
            package fx;
            public class DocBase {
                public void status(java.io.Serializable value) { }
            }
            """;

    /** The set whose companion reads and writes the state of each entity through the accessors resolved for it. */
    private static final String LIBRARY = """
            package fx;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            @StateGraphs public interface Library extends Machines { Docs docs(); }
            """;

    @Test
    void generatesTheFirstFittingReaderAndWriterInTheOrderTheConventionsTryThem() throws IOException {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Doc", DOC);
        sources.put("fx.DocBase", BASE);
        sources.put("fx.Library", LIBRARY);

        PinnedCompanions.assertGenerated("accessors", Compilation.compileTyped(sources));
    }

    @Test
    void reportsTiedWritersInTheOrderTheEntityHasThem() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Doc", TIED_DOC);
        sources.put("fx.DocBase", TIED_BASE);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(List.of("ERROR Docs.java:9:27 [" + DiagnosticCode.SG503 + "] Doc.status is ambiguous for property"
                + " 'status': java.io.Serializable and java.lang.Comparable<fx.DocStatus> all accept fx.DocStatus and"
                + " none is more specific than the others"), result.located(), result.describe());
    }
}
