package io.stategraph.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NullBoundariesTest {

    static Stream<Arguments> entries() {
        return Stream.of(
                Arguments.of("from", (Executable) () -> new EdgeSpec<String, DayOfWeek>(null, DayOfWeek.MONDAY, EdgeKind.STAY, "",
                        Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty())),
                Arguments.of("kind", (Executable) () -> new EdgeSpec<String, DayOfWeek>(DayOfWeek.MONDAY, DayOfWeek.MONDAY, null, "",
                        Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty())),
                Arguments.of("guards", (Executable) () -> new EdgeSpec<String, DayOfWeek>(DayOfWeek.MONDAY, DayOfWeek.MONDAY, EdgeKind.STAY, "",
                        Optional.empty(), null, Optional.empty(), List.of(), Optional.empty())),
                Arguments.of("condition", (Executable) () -> new WatchRule<String, DayOfWeek, String, DayOfWeek>(null, DayOfWeek.MONDAY)),
                Arguments.of("peer", (Executable) () -> new WatchSpec<String, DayOfWeek, String, DayOfWeek>(DayOfWeek.MONDAY, null,
                        (String e) -> List.of(), (String p) -> Optional.empty(), List.of())),
                Arguments.of("entity", (Executable) () -> new Outcome.Ignored<String, DayOfWeek>(null, DayOfWeek.MONDAY, DayOfWeek.MONDAY)),
                Arguments.of("records", (Executable) () -> new Outcome.Applied<String, DayOfWeek>("e", DayOfWeek.MONDAY, DayOfWeek.MONDAY, null, List.of())),
                Arguments.of("key", (Executable) () -> new ActionRecord(null, "e", DayOfWeek.MONDAY, DayOfWeek.MONDAY, "A", 0, ActionStatus.OK)),
                Arguments.of("state", (Executable) () -> WatchCondition.<String, String, DayOfWeek>allIn(null)),
                Arguments.of("name", (Executable) () -> new NamedCondition<String, String, DayOfWeek>(null,
                        (String e, java.util.Collection<? extends String> peers, PeerView<String, DayOfWeek> v) -> true)));
    }

    @ParameterizedTest
    @MethodSource("entries")
    void rejectsNullWithParameterName(String parameter, Executable call) {
        NullPointerException error = assertThrows(NullPointerException.class, call);
        assertTrue(error.getMessage().startsWith(parameter), error.getMessage());
    }
}
