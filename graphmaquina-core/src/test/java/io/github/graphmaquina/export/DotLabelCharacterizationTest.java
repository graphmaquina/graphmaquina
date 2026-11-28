package io.github.graphmaquina.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.TransitionKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins, by the SHA-256 of the rendered DOT taken on v2.0.9 (e5521d52), how {@link GraphvizExporter} writes the label
 * of an edge whose identifier, action, guards, recoveries, failure route and watch are made of plain text, of the XML
 * specials, of control characters (tabs, every line ending), of the two characters XML forbids, of backslashes and of
 * non-ASCII text, alone and mixed.
 */
class DotLabelCharacterizationTest {

    private static final List<String> PIECES = List.of("a", "Pay", " ", "&", "<", ">", "\"", "\t", "\n", "\r", "\r\n",
            "￾", "￿", "\u0001", "\u001F", "\\", "é", "-", "[x]", "");

    @Test
    void labelsOfEveryKindOfTextKeepTheirDot() {
        Random random = new Random(0xD07_1ABEL);
        MessageDigest digest = sha256();
        for (int sample = 0; sample < 3_000; sample++) {
            digest.update(GraphvizExporter.render(view(random)).getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("fb654e8b7a4bb52563cff7ea45db3c85147789a6eb37a4d308c9b0a12820d643",
                HexFormat.of().formatHex(digest.digest()));
    }

    private static GraphView view(Random random) {
        List<TransitionView> transitions = new ArrayList<>();
        for (int from = 0; from < 2; from++) {
            transitions.add(edge(random, "S" + from, "S" + (from + 1)));
        }
        return new GraphView("g", "S0", Set.of("S2"), List.of("S0", "S1", "S2"), transitions, List.of(), List.of(),
                List.of());
    }

    private static TransitionView edge(Random random, String from, String to) {
        Optional<String> action = random.nextBoolean() ? Optional.of(text(random)) : Optional.empty();
        List<RecoveryView> recoveries = random.nextBoolean() && action.isPresent()
                ? List.of(new RecoveryView(text(random), text(random), List.of(), random.nextBoolean()))
                : List.of();
        return new TransitionView(from, text(random), to,
                action.isPresent() ? TransitionKind.ACTION : TransitionKind.MOVE, action,
                List.of(text(random), text(random)), random.nextBoolean(), optional(random), optional(random),
                recoveries, random.nextBoolean(), Optional.empty(), random.nextBoolean());
    }

    private static Optional<String> optional(Random random) {
        return random.nextBoolean() ? Optional.of(text(random)) : Optional.empty();
    }

    private static String text(Random random) {
        StringBuilder text = new StringBuilder();
        for (int piece = random.nextInt(5); piece >= 0; piece--) {
            text.append(PIECES.get(random.nextInt(PIECES.size())));
        }
        return text.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }
}
