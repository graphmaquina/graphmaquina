package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WatchAndStructureErrorsTest {

    private static Compilation.Result watch(String watchLine) {
        return Compilation.compile(Fixtures.withPages(Fixtures.DOCS_HEADER + watchLine, Fixtures.DOCS_BODY));
    }

    @Test
    void builtInAndConditionRulesAreAccepted() {
        Compilation.Result builtIn = watch(Fixtures.DOCS_WATCH);
        assertTrue(builtIn.success(), builtIn.describe());
        Compilation.Result condition = watch("@Watch(state = \"REVIEW\", peer = Pages.class, peers = \"pages\", owner = \"doc\", by = DocActions.class,\n"
                + "       rules = { @When(condition = \"allPagesDone\", goTo = \"PUBLISHED\"), @When(noneIn = \"OPEN\", goTo = \"DRAFT\") })\n");
        assertTrue(condition.success(), condition.describe());
    }

    @Test
    void sg508RuleShape() {
        assertTrue(watch(Fixtures.DOCS_WATCH.replace("@When(allIn = \"DONE\"", "@When(allIn = \"DONE\", anyIn = \"DONE\"")).hasError("SG508"));
        assertTrue(watch(Fixtures.DOCS_WATCH.replace("@When(allIn = \"DONE\", goTo", "@When(goTo")).hasError("SG508"));
        assertTrue(watch(Fixtures.DOCS_WATCH.replace("allIn = \"DONE\"", "condition = \"allPagesDone\"")).hasError("SG508"));
    }

    /**
     * peer is declared Class&lt;? extends Machine&lt;?, ?&gt;&gt;, so a class that is not a Machine at all is a javac type
     * error and no round runs; SG509 is the rule for a Machine that carries no @StateGraph.
     */
    @Test
    void sg509PeerMustBeAStateGraphInterface() {
        Map<String, String> sources = Fixtures.withPages(
                Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH.replace("Pages.class", "PlainPages.class"), Fixtures.DOCS_BODY);
        sources.put("fx.PlainPages", Fixtures.PLAIN_PAGES);
        Compilation.Result result = Compilation.compile(sources);
        assertTrue(result.hasError("SG509"), result.describe());
    }

    @Test
    void sg503PeersAndOwnerProperties() {
        assertTrue(watch(Fixtures.DOCS_WATCH.replace("peers = \"pages\"", "peers = \"sheets\"")).hasError("SG503"));
        assertTrue(watch(Fixtures.DOCS_WATCH.replace("owner = \"doc\"", "owner = \"id\"")).hasError("SG503"));
    }

    @Test
    void sg501InPeerStatesAndGoTo() {
        Compilation.Result peerState = watch(Fixtures.DOCS_WATCH.replace("allIn = \"DONE\"", "allIn = \"DONEE\""));
        assertTrue(peerState.hasError("SG501"), peerState.describe());
        assertTrue(peerState.errors().get(0).contains("PageStatus"), peerState.describe());
        assertTrue(watch(Fixtures.DOCS_WATCH.replace("goTo = \"PUBLISHED\"", "goTo = \"PUBLISHEDD\"")).hasError("SG501"));
    }

    @Test
    void sg403TwoWatchesAndSg404NoRules() {
        assertTrue(watch(Fixtures.DOCS_WATCH + Fixtures.DOCS_WATCH).hasError("SG403"));
        assertTrue(watch(Fixtures.DOCS_WATCH.replace("rules = @When(allIn = \"DONE\", goTo = \"PUBLISHED\")", "rules = {}")).hasError("SG404"));
    }

    @Test
    void structuralCodesComeOutOfJavac() {
        Compilation.Result unreachable = Compilation.compile(Fixtures.withDocs(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace("@Move(from = \"DRAFT\", to = \"REVIEW\")\n    Outcome<Doc, DocStatus> submit(Doc doc);\n", "")));
        assertTrue(unreachable.hasError("SG203"), unreachable.describe());
        assertEquals(13, unreachable.lineOf("SG203"), unreachable.describe());

        Compilation.Result terminalExits = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("terminals = {\"ARCHIVED\"}", "terminals = {\"ARCHIVED\", \"PUBLISHED\"}"), Fixtures.DOCS_BODY));
        assertTrue(terminalExits.hasError("SG202"), terminalExits.describe());

        Compilation.Result cycle = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace(", allowsCycle = \"REVIEW\"", ""), Fixtures.DOCS_BODY));
        assertTrue(cycle.hasError("SG204"), cycle.describe());

        Compilation.Result deadCycle = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("allowsCycle = \"REVIEW\"", "allowsCycle = {\"REVIEW\", \"PUBLISHED\"}"), Fixtures.DOCS_BODY));
        assertTrue(deadCycle.hasError("SG205"), deadCycle.describe());

        Compilation.Result badRoute = Compilation.compile(Fixtures.withDocs(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace("onFailure = \"DRAFT\"", "onFailure = \"ARCHIVED\"")));
        assertTrue(badRoute.hasError("SG301"), badRoute.describe());

        assertTrue(watch(Fixtures.DOCS_WATCH.replace("goTo = \"PUBLISHED\"", "goTo = \"ARCHIVED\"")).hasError("SG401"));
        assertTrue(watch(Fixtures.DOCS_WATCH.replace("state = \"REVIEW\"", "state = \"ARCHIVED\"")).hasError("SG402"));
    }
}
