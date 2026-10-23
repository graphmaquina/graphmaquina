package io.github.graphmaquina.fixtures;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import java.util.function.UnaryOperator;

/**
 * Graph definitions of the doc fixture machine: DRAFT -SUBMIT-> REVIEW, DRAFT -DISCARD-> ARCHIVED,
 * REVIEW -PUBLISH-> PUBLISHED and PUBLISHED -ARCHIVE-> ARCHIVED, where the caller shapes the SUBMIT edge and, if it
 * wants, the DISCARD edge.
 */
public final class DocGraphs {

    private DocGraphs() { }

    /**
     * DRAFT -SUBMIT-> REVIEW, shaped by {@code submit}; DRAFT -DISCARD-> ARCHIVED, shaped by {@code discard};
     * REVIEW -PUBLISH-> PUBLISHED and PUBLISHED -ARCHIVE-> ARCHIVED are plain moves.
     */
    public static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit,
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> discard) {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived");
        graph = submit.apply(graph.transition(SUBMIT, DRAFT, REVIEW)).endTransition();
        graph = discard.apply(graph.transition(DISCARD, DRAFT, ARCHIVED)).endTransition();
        return graph.transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    /** {@link #docs(UnaryOperator, UnaryOperator)} with DISCARD as a plain move. */
    public static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return docs(submit, UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity());
    }

    /** {@link #docs(UnaryOperator)} with SUBMIT as a plain move too. */
    public static GraphDefinition<Doc, DocStatus, DocTransition> plain() {
        return docs(UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity());
    }
}
