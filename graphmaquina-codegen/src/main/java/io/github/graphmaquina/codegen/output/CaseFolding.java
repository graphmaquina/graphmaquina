package io.github.graphmaquina.codegen.output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Tells names that differ only in case apart, and the volumes that do not. The generator and the annotation processor
 * both refuse two names a volume would take for one file, where that volume holds their files, and only there.
 */
public final class CaseFolding {

    private CaseFolding() {
    }

    /**
     * {@code name} with every character in one case, as {@link String#equalsIgnoreCase} compares characters, so
     * two names that differ only in case have the same key.
     */
    public static String key(String name) {
        StringBuilder key = new StringBuilder(name.length());
        name.codePoints().forEach((int codePoint) ->
                key.appendCodePoint(Character.toLowerCase(Character.toUpperCase(codePoint))));
        return key.toString();
    }

    /**
     * Whether the volume of {@code directory} does not tell names apart by case. The directory, or while it does not
     * exist the nearest directory above it that does, is looked up under its own name with one letter in the other
     * case, going further up while a name has no letter that has another case; the volume does not tell names apart
     * when that lookup finds the very same directory. The directory asked is therefore the one that holds the
     * directory looked up: the parent of {@code directory}, or of the existing ancestor looked up instead.
     *
     * @throws IOException if a directory looked up cannot be examined
     */
    public static boolean folds(Path directory) throws IOException {
        Path probed = directory.toAbsolutePath().normalize();
        while (probed != null) {
            Path name = probed.getFileName();
            if (name != null && Files.exists(probed)) {
                Optional<String> otherCase = otherCase(name.toString());
                if (otherCase.isPresent()) {
                    Path other = probed.resolveSibling(otherCase.get());
                    return Files.exists(other) && Files.isSameFile(other, probed);
                }
            }
            probed = probed.getParent();
        }
        return false;
    }

    /** {@code name} with its first letter that has another case in that case; empty when no letter has one. */
    private static Optional<String> otherCase(String name) {
        for (int index = 0; index < name.length(); index++) {
            char letter = name.charAt(index);
            char other = Character.isUpperCase(letter) ? Character.toLowerCase(letter) : Character.toUpperCase(letter);
            if (other != letter) {
                return Optional.of(name.substring(0, index) + other + name.substring(index + 1));
            }
        }
        return Optional.empty();
    }
}
