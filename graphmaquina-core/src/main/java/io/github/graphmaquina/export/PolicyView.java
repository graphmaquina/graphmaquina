package io.github.graphmaquina.export;

import java.util.List;
import lombok.NonNull;

/**
 * The rejection policy of one state, by names. It is drawn as an attribute of its node, never as an arrow:
 * a rejection is not an edge.
 *
 * @param state the state that declares it
 * @param otherwise the reason used for any identifier without its own
 * @param rejections the reasons declared per identifier, rendered as {@code ID: reason}
 */
public record PolicyView(@NonNull String state, @NonNull String otherwise, @NonNull List<String> rejections) {

    public PolicyView {
        rejections = List.copyOf(rejections);
    }
}
