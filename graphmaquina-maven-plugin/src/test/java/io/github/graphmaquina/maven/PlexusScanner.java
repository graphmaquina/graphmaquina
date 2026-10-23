package io.github.graphmaquina.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;

/**
 * The decisions the {@code DirectoryScanner} of plexus-utils 4.0.2 makes about the files a scan includes, transcribed
 * from its sources with the file separator made a parameter, so that a test can run the scanner of
 * maven-compiler-plugin as it runs on a platform whose separator is not the one of the machine running the test.
 *
 * <p>Each method follows the method of plexus-utils its description names, behaviour for behaviour, with
 * {@code File.separatorChar} and {@code File.separator} replaced by the separator given: the normalization
 * {@code AbstractScanner.setIncludes} and {@code setExcludes} apply, {@code AbstractScanner.addDefaultExcludes}, the
 * {@code MatchPattern} the scanner builds of each pattern with its {@code matchPath} and {@code matchPatternStart},
 * the {@code SelectorUtils} methods they call, and the fast scan of {@code DirectoryScanner.scandir}, run over a tree
 * that holds the given files and the directories above them, every one of which a scan selects. The scanner is set up
 * as the source scanner of plexus-compiler-api 2.16.2 sets it up: case-sensitive, the default it keeps, with
 * {@code **} as the only include when there is none, and with the default excludes after the excludes.</p>
 *
 * <p>The four methods too complex to read in one piece, {@code scandir}, {@code SelectorUtils.match},
 * {@code SelectorUtils.matchAntPathPattern} and the {@code String[]} form of
 * {@code SelectorUtils.matchAntPathPatternStart}, are divided into methods named for the part they hold. The loops of
 * a walk run in the order of the original, and a check that the next part makes again is left to it; the nested
 * conditions under which {@code scandir} enters a directory are one expression. The walk over the characters of a
 * name and the walk over the names of a path stay two, as they are in plexus-utils.</p>
 */
final class PlexusScanner {

    /** {@code SelectorUtils.REGEX_HANDLER_PREFIX}. */
    private static final String REGEX_HANDLER_PREFIX = "%regex[";

    /** {@code SelectorUtils.ANT_HANDLER_PREFIX}. */
    private static final String ANT_HANDLER_PREFIX = "%ant[";

    /** {@code SelectorUtils.PATTERN_HANDLER_SUFFIX}. */
    private static final String PATTERN_HANDLER_SUFFIX = "]";

    /** {@code AbstractScanner.DEFAULTEXCLUDES}, in its order. */
    private static final List<String> DEFAULTEXCLUDES = List.of("**/*~", "**/#*#", "**/.#*", "**/%*%", "**/._*",
            "**/CVS", "**/CVS/**", "**/.cvsignore", "**/RCS", "**/RCS/**", "**/SCCS", "**/SCCS/**", "**/vssver.scc",
            "**/project.pj", "**/.svn", "**/.svn/**", "**/.arch-ids", "**/.arch-ids/**", "**/.bzr", "**/.bzr/**",
            "**/.MySCMServerInfo", "**/.DS_Store", "**/.metadata", "**/.metadata/**", "**/.hg", "**/.hg/**",
            "**/.git", "**/.git/**", "**/.gitignore", "**/BitKeeper", "**/BitKeeper/**", "**/ChangeSet",
            "**/ChangeSet/**", "**/_darcs", "**/_darcs/**", "**/.darcsrepo", "**/.darcsrepo/**", "**/-darcs-backup*",
            "**/.darcs-temp-mail");

    private final char separatorChar;
    private final String separator;
    private final List<MatchPattern> includesPatterns = new ArrayList<>();
    private final List<MatchPattern> excludesPatterns = new ArrayList<>();

    /**
     * A scanner of the given patterns on a platform whose file separator is {@code separatorChar}.
     *
     * @param includes the includes, none standing for {@code **}
     * @param excludes the excludes, which the default excludes follow
     * @param separatorChar the file separator of the platform the scanner runs on
     */
    PlexusScanner(List<String> includes, List<String> excludes, char separatorChar) {
        this.separatorChar = separatorChar;
        this.separator = String.valueOf(separatorChar);
        for (String include : includes) {
            includesPatterns.add(new MatchPattern(normalizePattern(include)));
        }
        if (includes.isEmpty()) {
            includesPatterns.add(new MatchPattern("**"));
        }
        for (String exclude : excludes) {
            excludesPatterns.add(new MatchPattern(normalizePattern(exclude)));
        }
        for (String exclude : DEFAULTEXCLUDES) {
            excludesPatterns.add(new MatchPattern(exclude.replace('/', separatorChar)));
        }
    }

    /**
     * The files a scan of a root that holds {@code files} includes.
     *
     * @param files the files under the root, their names separated by {@code /}
     * @return the included files, their names separated by {@code /}
     */
    Set<String> includedFiles(Set<String> files) {
        Set<String> included = new TreeSet<>();
        scandir(files, List.of(), "", included);
        return included;
    }

    /**
     * Whether the path of a file matches an include, as {@code AbstractScanner.isIncluded} tells it.
     *
     * @param path the path, its names separated by {@code /}
     * @return {@code true} if an include matches it
     */
    boolean isIncluded(String path) {
        String name = String.join(separator, path.split("/"));
        return matches(includesPatterns, name, tokenizePathToCharArray(name, separator));
    }

    /** {@code DirectoryScanner.scandir} during a fast scan, over the directory whose names are {@code directory}. */
    private void scandir(Set<String> files, List<String> directory, String vpath, Set<String> included) {
        Set<String> newfiles = namesBelow(files, directory, 0);
        Set<String> directories = namesBelow(files, directory, 1);
        for (String newfile : newfiles) {
            String name = vpath + newfile;
            char[][] tokenizedName = tokenizePathToCharArray(name, separator);
            List<String> child = new ArrayList<>(directory);
            child.add(newfile);
            if (directories.contains(newfile)) {
                if (entersDirectory(name, tokenizedName)) {
                    scandir(files, child, name + separator, included);
                }
            } else if (isIncludedAndNotExcluded(name, tokenizedName)) {
                included.add(String.join("/", child));
            }
        }
    }

    /**
     * The names that follow {@code directory} in the paths of {@code files} below it that go {@code deeper} more
     * names further: of every path below it for 0, of those that pass through a directory below it for 1.
     */
    private static Set<String> namesBelow(Set<String> files, List<String> directory, int deeper) {
        Set<String> names = new TreeSet<>();
        for (String file : files) {
            List<String> path = List.of(file.split("/"));
            if (path.size() > directory.size() + deeper && path.subList(0, directory.size()).equals(directory)) {
                names.add(path.get(directory.size()));
            }
        }
        return names;
    }

    /**
     * Whether the scan enters the directory {@code name}: an include matches it and no exclude does, or an include
     * could match a path below it, which {@code DirectoryScanner.scandir} asks in that order.
     */
    private boolean entersDirectory(String name, char[][] tokenizedName) {
        return isIncludedAndNotExcluded(name, tokenizedName) || couldHoldIncluded(name);
    }

    /** Whether an include matches {@code name} and no exclude does. */
    private boolean isIncludedAndNotExcluded(String name, char[][] tokenizedName) {
        return matches(includesPatterns, name, tokenizedName) && !matches(excludesPatterns, name, tokenizedName);
    }

    /** {@code AbstractScanner.normalizePattern}. */
    private String normalizePattern(String pattern) {
        String normalized = pattern.trim();
        if (normalized.startsWith(REGEX_HANDLER_PREFIX)) {
            if (separatorChar == '\\') {
                normalized = normalized.replace("/", "\\\\");
            } else {
                normalized = normalized.replace("\\\\", "/");
            }
        } else {
            normalized = normalized.replace(separatorChar == '/' ? '\\' : '/', separatorChar);
            if (normalized.endsWith(separator)) {
                normalized += "**";
            }
        }
        return normalized;
    }

    /** {@code MatchPatterns.matches(String, char[][], boolean)}. */
    private static boolean matches(List<MatchPattern> patterns, String name, char[][] tokenizedNameChar) {
        for (MatchPattern pattern : patterns) {
            if (pattern.matchPath(name, tokenizedNameChar)) {
                return true;
            }
        }
        return false;
    }

    /** {@code AbstractScanner.couldHoldIncluded}, through {@code MatchPatterns.matchesPatternStart}. */
    private boolean couldHoldIncluded(String name) {
        for (MatchPattern includesPattern : includesPatterns) {
            if (includesPattern.matchPatternStart(name)) {
                return true;
            }
        }
        return false;
    }

    /** {@code SelectorUtils.isRegexPrefixedPattern}. */
    private static boolean isRegexPrefixedPattern(String pattern) {
        return pattern.length() > (REGEX_HANDLER_PREFIX.length() + PATTERN_HANDLER_SUFFIX.length() + 1)
                && pattern.startsWith(REGEX_HANDLER_PREFIX)
                && pattern.endsWith(PATTERN_HANDLER_SUFFIX);
    }

    /** {@code SelectorUtils.isAntPrefixedPattern}. */
    private static boolean isAntPrefixedPattern(String pattern) {
        return pattern.length() > (ANT_HANDLER_PREFIX.length() + PATTERN_HANDLER_SUFFIX.length() + 1)
                && pattern.startsWith(ANT_HANDLER_PREFIX)
                && pattern.endsWith(PATTERN_HANDLER_SUFFIX);
    }

    /** {@code SelectorUtils.matchAntPathPatternStart(MatchPattern, String, String, boolean)}. */
    private static boolean matchAntPathPatternStart(MatchPattern pattern, String str, String separator) {
        if (str.startsWith(separator) != pattern.source.startsWith(separator)) {
            return false;
        }
        return matchAntPathPatternStart(pattern.tokenized, str, separator);
    }

    /** {@code SelectorUtils.matchAntPathPatternStart(String[], String, String, boolean)}. */
    private static boolean matchAntPathPatternStart(String[] patDirs, String str, String separator) {
        String[] strDirs = tokenizePathToString(str, separator);
        int matched = matchedHead(patDirs, strDirs);
        if (matched < 0) {
            return false;
        }
        return matched >= strDirs.length || matched < patDirs.length;
    }

    /**
     * The loop of {@code SelectorUtils.matchAntPathPatternStart(String[], String, String, boolean)} that matches the
     * names of the pattern with those of the string up to the first {@code **}: how many it matched, or -1 when one
     * of them does not match.
     */
    private static int matchedHead(String[] patDirs, String[] strDirs) {
        int matched = 0;
        while (matched < patDirs.length && matched < strDirs.length) {
            String patDir = patDirs[matched];
            if (patDir.equals("**")) {
                break;
            }
            if (!match(patDir.toCharArray(), strDirs[matched].toCharArray())) {
                return -1;
            }
            matched++;
        }
        return matched;
    }

    /**
     * {@code SelectorUtils.matchAntPathPattern(char[][], char[][], boolean)}, in the phases it runs one after the
     * other: the head, the tail and the middle.
     */
    private static boolean matchAntPathPattern(char[][] patDirs, char[][] strDirs) {
        int head = matchedHead(patDirs, strDirs);
        if (head < 0) {
            return false;
        }
        int patIdxEnd = patDirs.length - 1;
        int strIdxEnd = strDirs.length - 1;
        if (head > strIdxEnd) {
            return onlyDoubleStars(patDirs, head, patIdxEnd);
        } else if (head > patIdxEnd) {
            return false;
        }
        int tail = matchedTail(patDirs, head, strDirs, head);
        if (tail < 0) {
            return false;
        }
        return matchInside(patDirs, head, patIdxEnd - tail, strDirs, head, strIdxEnd - tail);
    }

    /**
     * The first loop of {@code matchAntPathPattern}: how many names at the start of the string the names of the
     * pattern up to its first {@code **} match, or -1 when one does not match.
     */
    private static int matchedHead(char[][] patDirs, char[][] strDirs) {
        int matched = 0;
        while (matched < patDirs.length && matched < strDirs.length) {
            char[] patDir = patDirs[matched];
            if (isDoubleStar(patDir)) {
                break;
            }
            if (!match(patDir, strDirs[matched])) {
                return -1;
            }
            matched++;
        }
        return matched;
    }

    /**
     * The second loop of {@code matchAntPathPattern}: how many names at the end of the string the names at the end
     * of the pattern, down to its last {@code **}, match, or -1 when one does not match. The names before
     * {@code patIdxStart} and {@code strIdxStart} are taken.
     */
    private static int matchedTail(char[][] patDirs, int patIdxStart, char[][] strDirs, int strIdxStart) {
        int patIdxEnd = patDirs.length - 1;
        int strIdxEnd = strDirs.length - 1;
        int matched = 0;
        while (patIdxStart <= patIdxEnd && strIdxStart <= strIdxEnd) {
            char[] patDir = patDirs[patIdxEnd];
            if (isDoubleStar(patDir)) {
                break;
            }
            if (!match(patDir, strDirs[strIdxEnd])) {
                return -1;
            }
            patIdxEnd--;
            strIdxEnd--;
            matched++;
        }
        return matched;
    }

    /**
     * The third loop of {@code matchAntPathPattern} and what follows it: the names of the pattern between its
     * first and its last {@code **} match the rest of the string, each run of them between two {@code **} at its
     * first place.
     */
    private static boolean matchInside(char[][] patDirs, int patIdxStart, int patIdxEnd, char[][] strDirs,
            int strIdxStart, int strIdxEnd) {
        while (patIdxStart != patIdxEnd && strIdxStart <= strIdxEnd) {
            int patIdxTmp = indexOfDoubleStar(patDirs, patIdxStart + 1, patIdxEnd);
            if (patIdxTmp == patIdxStart + 1) {
                patIdxStart++;
                continue;
            }
            int patLength = (patIdxTmp - patIdxStart - 1);
            int strLength = (strIdxEnd - strIdxStart + 1);
            int foundIdx = indexOfNames(patDirs, patIdxStart + 1, patLength, strDirs, strIdxStart, strLength);
            if (foundIdx == -1) {
                return false;
            }
            patIdxStart = patIdxTmp;
            strIdxStart = foundIdx + patLength;
        }
        return onlyDoubleStars(patDirs, patIdxStart, patIdxEnd);
    }

    /** The index of the first {@code **} among the names {@code from} to {@code to} of the pattern, or -1. */
    private static int indexOfDoubleStar(char[][] patDirs, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (isDoubleStar(patDirs[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The first index, from {@code strIdxStart} on, where the {@code patLength} names of the pattern from
     * {@code patStart} match the names of the string, among the {@code strLength} names that remain, or -1.
     */
    private static int indexOfNames(char[][] patDirs, int patStart, int patLength, char[][] strDirs, int strIdxStart,
            int strLength) {
        strLoop:
        for (int i = 0; i <= strLength - patLength; i++) {
            for (int j = 0; j < patLength; j++) {
                if (!match(patDirs[patStart + j], strDirs[strIdxStart + i + j])) {
                    continue strLoop;
                }
            }
            return strIdxStart + i;
        }
        return -1;
    }

    /** Whether the names {@code from} to {@code to} of the pattern are all {@code **}. */
    private static boolean onlyDoubleStars(char[][] patDirs, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (!isDoubleStar(patDirs[i])) {
                return false;
            }
        }
        return true;
    }

    /** {@code SelectorUtils.isDoubleStar}. */
    private static boolean isDoubleStar(char[] patDir) {
        return patDir != null && patDir.length == 2 && patDir[0] == '*' && patDir[1] == '*';
    }

    /**
     * {@code SelectorUtils.match(char[], char[], boolean)}, case-sensitive, in the phases it runs one after the
     * other: the head, the tail and the middle.
     */
    private static boolean match(char[] patArr, char[] strArr) {
        if (!containsStar(patArr)) {
            return matchesWithoutStar(patArr, strArr);
        }
        if (patArr.length == 1) {
            return true;
        }
        int head = matchedHead(patArr, strArr);
        if (head < 0) {
            return false;
        }
        int tail = matchedTail(patArr, strArr, head);
        if (tail < 0) {
            return false;
        }
        return matchInside(patArr, head, patArr.length - 1 - tail, strArr, head, strArr.length - 1 - tail);
    }

    /** Whether the pattern holds a {@code *}. */
    private static boolean containsStar(char[] patArr) {
        for (char aPatArr : patArr) {
            if (aPatArr == '*') {
                return true;
            }
        }
        return false;
    }

    /** The case of {@code match} where the pattern holds no {@code *}: the string must be as long as the pattern. */
    private static boolean matchesWithoutStar(char[] patArr, char[] strArr) {
        if (patArr.length != strArr.length) {
            return false;
        }
        for (int i = 0; i < patArr.length; i++) {
            if (!matchesChar(patArr[i], strArr[i])) {
                return false;
            }
        }
        return true;
    }

    /** Whether the pattern character {@code ch}, which may be {@code ?}, matches the character {@code strCh}. */
    private static boolean matchesChar(char ch, char strCh) {
        return ch == '?' || ch == strCh;
    }

    /**
     * The first loop of {@code match}: how many characters at the start of the string the characters of the pattern
     * up to its first {@code *} match, or -1 when one does not match.
     */
    private static int matchedHead(char[] patArr, char[] strArr) {
        int matched = 0;
        while (patArr[matched] != '*' && matched < strArr.length) {
            if (!matchesChar(patArr[matched], strArr[matched])) {
                return -1;
            }
            matched++;
        }
        return matched;
    }

    /**
     * The second loop of {@code match}: how many characters at the end of the string the characters at the end of
     * the pattern, down to its last {@code *}, match, or -1 when one does not match. The characters of the string
     * before {@code strIdxStart} are taken.
     */
    private static int matchedTail(char[] patArr, char[] strArr, int strIdxStart) {
        int patIdxEnd = patArr.length - 1;
        int strIdxEnd = strArr.length - 1;
        int matched = 0;
        while (patArr[patIdxEnd] != '*' && strIdxStart <= strIdxEnd) {
            if (!matchesChar(patArr[patIdxEnd], strArr[strIdxEnd])) {
                return -1;
            }
            patIdxEnd--;
            strIdxEnd--;
            matched++;
        }
        return matched;
    }

    /**
     * The third loop of {@code match} and what follows it: the characters of the pattern between its first and its
     * last {@code *} match the rest of the string, each run of them between two {@code *} at its first place.
     */
    private static boolean matchInside(char[] patArr, int patIdxStart, int patIdxEnd, char[] strArr, int strIdxStart,
            int strIdxEnd) {
        while (patIdxStart != patIdxEnd && strIdxStart <= strIdxEnd) {
            int patIdxTmp = indexOfStar(patArr, patIdxStart + 1, patIdxEnd);
            if (patIdxTmp == patIdxStart + 1) {
                patIdxStart++;
                continue;
            }
            int patLength = (patIdxTmp - patIdxStart - 1);
            int strLength = (strIdxEnd - strIdxStart + 1);
            int foundIdx = indexOfChars(patArr, patIdxStart + 1, patLength, strArr, strIdxStart, strLength);
            if (foundIdx == -1) {
                return false;
            }
            patIdxStart = patIdxTmp;
            strIdxStart = foundIdx + patLength;
        }
        return onlyStars(patArr, patIdxStart, patIdxEnd);
    }

    /** The index of the first {@code *} among the characters {@code from} to {@code to} of the pattern, or -1. */
    private static int indexOfStar(char[] patArr, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (patArr[i] == '*') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The first index, from {@code strIdxStart} on, where the {@code patLength} characters of the pattern from
     * {@code patStart} match the characters of the string, among the {@code strLength} that remain, or -1.
     */
    private static int indexOfChars(char[] patArr, int patStart, int patLength, char[] strArr, int strIdxStart,
            int strLength) {
        strLoop:
        for (int i = 0; i <= strLength - patLength; i++) {
            for (int j = 0; j < patLength; j++) {
                if (!matchesChar(patArr[patStart + j], strArr[strIdxStart + i + j])) {
                    continue strLoop;
                }
            }
            return strIdxStart + i;
        }
        return -1;
    }

    /** Whether the characters {@code from} to {@code to} of the pattern are all {@code *}. */
    private static boolean onlyStars(char[] patArr, int from, int to) {
        for (int i = from; i <= to; i++) {
            if (patArr[i] != '*') {
                return false;
            }
        }
        return true;
    }

    /** {@code MatchPattern.tokenizePathToString}. */
    private static String[] tokenizePathToString(String path, String separator) {
        List<String> ret = new ArrayList<>();
        StringTokenizer st = new StringTokenizer(path, separator);
        while (st.hasMoreTokens()) {
            ret.add(st.nextToken());
        }
        return ret.toArray(new String[0]);
    }

    /** {@code MatchPattern.tokenizePathToCharArray}. */
    private static char[][] tokenizePathToCharArray(String path, String separator) {
        String[] tokenizedName = tokenizePathToString(path, separator);
        char[][] tokenizedNameChar = new char[tokenizedName.length][];
        for (int i = 0; i < tokenizedName.length; i++) {
            tokenizedNameChar[i] = tokenizedName[i].toCharArray();
        }
        return tokenizedNameChar;
    }

    /** {@code MatchPattern}, built as {@code MatchPattern.fromString} builds it, with the separator of the scanner. */
    private final class MatchPattern {

        private final String source;
        private final Optional<String> regexPattern;
        private final String[] tokenized;
        private final char[][] tokenizedChar;

        MatchPattern(String source) {
            this.regexPattern = isRegexPrefixedPattern(source)
                    ? Optional.of(source.substring(REGEX_HANDLER_PREFIX.length(),
                            source.length() - PATTERN_HANDLER_SUFFIX.length()))
                    : Optional.empty();
            this.source = isAntPrefixedPattern(source)
                    ? source.substring(ANT_HANDLER_PREFIX.length(), source.length() - PATTERN_HANDLER_SUFFIX.length())
                    : source;
            this.tokenized = tokenizePathToString(this.source, separator);
            this.tokenizedChar = new char[tokenized.length][];
            for (int i = 0; i < tokenized.length; i++) {
                tokenizedChar[i] = tokenized[i].toCharArray();
            }
        }

        /** {@code MatchPattern.matchPath(String, char[][], boolean)}. */
        boolean matchPath(String str, char[][] strDirs) {
            if (regexPattern.isPresent()) {
                return str.matches(regexPattern.get());
            }
            return matchAntPathPattern(tokenizedChar, strDirs);
        }

        /** {@code MatchPattern.matchPatternStart}. */
        boolean matchPatternStart(String str) {
            if (regexPattern.isPresent()) {
                return true;
            }
            String altStr = str.replace('\\', '/');
            return matchAntPathPatternStart(this, str, separator) || matchAntPathPatternStart(this, altStr, "/");
        }
    }
}
