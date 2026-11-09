package io.github.graphmaquina.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The colors of a Graphviz drawing: the default palette, one wither per role that changes that role alone, and the
 * refusal of anything but a hex color.
 */
class GraphvizThemeTest {

    private static final String CHANGED = "#0a0b0c";

    /** Each role: its name, its accessor and its wither. */
    private record Role(String name, Function<GraphvizTheme, String> get,
                        BiFunction<GraphvizTheme, String, GraphvizTheme> with) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Stream<Role> roles() {
        return Stream.of(
                new Role("canvas", GraphvizTheme::canvas, GraphvizTheme::withCanvas),
                new Role("ink", GraphvizTheme::ink, GraphvizTheme::withInk),
                new Role("muted", GraphvizTheme::muted, GraphvizTheme::withMuted),
                new Role("stateFill", GraphvizTheme::stateFill, GraphvizTheme::withStateFill),
                new Role("automatic", GraphvizTheme::automatic, GraphvizTheme::withAutomatic),
                new Role("clusterFill", GraphvizTheme::clusterFill, GraphvizTheme::withClusterFill),
                new Role("clusterBorder", GraphvizTheme::clusterBorder, GraphvizTheme::withClusterBorder),
                new Role("watch", GraphvizTheme::watch, GraphvizTheme::withWatch),
                new Role("watchFill", GraphvizTheme::watchFill, GraphvizTheme::withWatchFill));
    }

    @Test
    void defaultsAreTheSitePalette() {
        assertEquals(new GraphvizTheme("#ffffff", "#1b1f3a", "#4f5677", "#ffffff", "#2457d6", "#f5f6fb", "#d4d8ea",
                "#a85a00", "#fff6e5"), GraphvizTheme.defaults());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roles")
    void aWitherChangesItsRoleAlone(Role role) {
        GraphvizTheme defaults = GraphvizTheme.defaults();
        GraphvizTheme changed = role.with().apply(defaults, CHANGED);
        assertEquals(CHANGED, role.get().apply(changed));
        roles().filter((Role other) -> !other.name().equals(role.name()))
                .forEach((Role other) -> assertEquals(other.get().apply(defaults), other.get().apply(changed),
                        other.name()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roles")
    void aWitherRefusesNull(Role role) {
        assertThrows(NullPointerException.class, () -> role.with().apply(GraphvizTheme.defaults(), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"#AABBCC", "#aabbcc", "#00000000", "#FfEe0080"})
    void hexColorsWithOrWithoutAlphaAreAccepted(String color) {
        assertEquals(color, GraphvizTheme.defaults().withInk(color).ink());
    }

    static Stream<Arguments> refusedColors() {
        return Stream.of("", "red", "ffffff", "#fff", "#fffffff", "#ggggggg", "#ffffff ", " #ffffff",
                "#ffffff\n", "#1234567890").map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("refusedColors")
    void anythingButAHexColorIsRefusedNamingTheRole(String color) {
        List<Role> all = roles().toList();
        for (Role role : all) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> role.with().apply(GraphvizTheme.defaults(), color), role.name());
            assertEquals(role.name() + " must be a #RRGGBB or #RRGGBBAA color: \"" + color + "\"",
                    refused.getMessage());
        }
    }
}
