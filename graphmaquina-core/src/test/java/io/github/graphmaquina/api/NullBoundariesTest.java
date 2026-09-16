package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NullBoundariesTest {

    static Stream<Arguments> entries() {
        return Stream.of(
                Arguments.of("from", (Executable) () -> new TransitionSpec<String, DayOfWeek, Month>(null, Month.JANUARY,
                        DayOfWeek.MONDAY, Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty(),
                        Optional.empty())),
                Arguments.of("transition", (Executable) () -> new TransitionSpec<String, DayOfWeek, Month>(DayOfWeek.MONDAY,
                        null, DayOfWeek.MONDAY, Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty(),
                        Optional.empty())),
                Arguments.of("guards", (Executable) () -> new TransitionSpec<String, DayOfWeek, Month>(DayOfWeek.MONDAY,
                        Month.JANUARY, DayOfWeek.MONDAY, Optional.empty(), null, Optional.empty(), List.of(),
                        Optional.empty(), Optional.empty())),
                Arguments.of("afterCommit", (Executable) () -> new TransitionSpec<String, DayOfWeek, Month>(DayOfWeek.MONDAY,
                        Month.JANUARY, DayOfWeek.TUESDAY, Optional.empty(), List.of(), Optional.empty(), List.of(),
                        Optional.empty(), null)),
                Arguments.of("from", (Executable) () -> new TransitionKey<DayOfWeek, Month>(null, Month.JANUARY)),
                Arguments.of("state", (Executable) () -> new StatePolicy<DayOfWeek, Month>(null, "why", Map.of())),
                Arguments.of("otherwise", (Executable) () -> new StatePolicy<DayOfWeek, Month>(DayOfWeek.MONDAY, null, Map.of())),
                Arguments.of("condition", (Executable) () ->
                        new WatchRule<String, DayOfWeek, Month, String, DayOfWeek, Month>(null, Month.JANUARY)),
                Arguments.of("transition", (Executable) () -> new WatchRule<String, DayOfWeek, Month, String, DayOfWeek, Month>(
                        new NamedCondition<>("c", (String e, Collection<? extends String> peers,
                                PeerView<String, DayOfWeek, Month> v) -> true), null)),
                Arguments.of("peer", (Executable) () -> new WatchSpec<String, DayOfWeek, Month, String, DayOfWeek, Month>(
                        DayOfWeek.MONDAY, null, (String e) -> List.of(), (String p) -> Optional.empty(), List.of())),
                Arguments.of("entity", (Executable) () ->
                        new Outcome.Ignored<String, DayOfWeek, Month>(null, DayOfWeek.MONDAY, IgnoreReason.NO_WATCH)),
                Arguments.of("reason", (Executable) () ->
                        new Outcome.Ignored<String, DayOfWeek, Month>("e", DayOfWeek.MONDAY, null)),
                Arguments.of("records", (Executable) () -> new Outcome.Applied<String, DayOfWeek, Month>("e",
                        DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.MONDAY, null, List.of())),
                Arguments.of("key", (Executable) () -> new ActionRecord(null, "e", DayOfWeek.MONDAY, Month.JANUARY,
                        DayOfWeek.MONDAY, "A", 0, ActionStatus.OK)),
                Arguments.of("state", (Executable) () -> WatchCondition.<String, String, DayOfWeek, Month>allIn(null)),
                Arguments.of("name", (Executable) () -> new NamedCondition<String, String, DayOfWeek, Month>(null,
                        (String e, Collection<? extends String> peers, PeerView<String, DayOfWeek, Month> v) -> true)),
                Arguments.of("hook", (Executable) () -> new HookRef<String, DayOfWeek, Month>("H", null)),
                Arguments.of("transitions", (Executable) () -> MachineKey.of(String.class, DayOfWeek.class, null)));
    }

    @ParameterizedTest
    @MethodSource("entries")
    void rejectsNullWithParameterName(String parameter, Executable call) {
        NullPointerException error = assertThrows(NullPointerException.class, call);
        assertTrue(error.getMessage().startsWith(parameter), error.getMessage());
    }
}
