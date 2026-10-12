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
 * <p>Each method follows the method of plexus-utils its description names, statement by statement, with
 * {@code File.separatorChar} and {@code File.separator} replaced by the separator given: the normalization
 * {@code AbstractScanner.setIncludes} and {@code setExcludes} apply, {@code AbstractScanner.addDefaultExcludes}, the
 * {@code MatchPattern} the scanner builds of each pattern with its {@code matchPath} and {@code matchPatternStart},
 * the {@code SelectorUtils} methods they call, and the fast scan of {@code DirectoryScanner.scandir}, run over a tree
 * that holds the given files and the directories above them, every one of which a scan selects. The scanner is set up
 * as the source scanner of plexus-compiler-api 2.16.2 sets it up: case-sensitive, the default it keeps, with
 * {@code **} as the only include when there is none, and with the default excludes after the excludes.</p>
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
        Set<String> newfiles = new TreeSet<>();
        Set<String> directories = new TreeSet<>();
        for (String file : files) {
            List<String> names = List.of(file.split("/"));
            if (names.size() > directory.size() && names.subList(0, directory.size()).equals(directory)) {
                newfiles.add(names.get(directory.size()));
                if (names.size() > directory.size() + 1) {
                    directories.add(names.get(directory.size()));
                }
            }
        }
        for (String newfile : newfiles) {
            String name = vpath + newfile;
            char[][] tokenizedName = tokenizePathToCharArray(name, separator);
            List<String> child = new ArrayList<>(directory);
            child.add(newfile);
            if (directories.contains(newfile)) {
                if (matches(includesPatterns, name, tokenizedName)) {
                    if (!matches(excludesPatterns, name, tokenizedName)) {
                        scandir(files, child, name + separator, included);
                    } else if (couldHoldIncluded(name)) {
                        scandir(files, child, name + separator, included);
                    }
                } else if (couldHoldIncluded(name)) {
                    scandir(files, child, name + separator, included);
                }
            } else if (matches(includesPatterns, name, tokenizedName)
                    && !matches(excludesPatterns, name, tokenizedName)) {
                included.add(String.join("/", child));
            }
        }
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
        int patIdxStart = 0;
        int patIdxEnd = patDirs.length - 1;
        int strIdxStart = 0;
        int strIdxEnd = strDirs.length - 1;
        while (patIdxStart <= patIdxEnd && strIdxStart <= strIdxEnd) {
            String patDir = patDirs[patIdxStart];
            if (patDir.equals("**")) {
                break;
            }
            if (!match(patDir.toCharArray(), strDirs[strIdxStart].toCharArray())) {
                return false;
            }
            patIdxStart++;
            strIdxStart++;
        }
        return strIdxStart > strIdxEnd || patIdxStart <= patIdxEnd;
    }

    /** {@code SelectorUtils.matchAntPathPattern(char[][], char[][], boolean)}. */
    private static boolean matchAntPathPattern(char[][] patDirs, char[][] strDirs) {
        int patIdxStart = 0;
        int patIdxEnd = patDirs.length - 1;
        int strIdxStart = 0;
        int strIdxEnd = strDirs.length - 1;
        while (patIdxStart <= patIdxEnd && strIdxStart <= strIdxEnd) {
            char[] patDir = patDirs[patIdxStart];
            if (isDoubleStar(patDir)) {
                break;
            }
            if (!match(patDir, strDirs[strIdxStart])) {
                return false;
            }
            patIdxStart++;
            strIdxStart++;
        }
        if (strIdxStart > strIdxEnd) {
            for (int i = patIdxStart; i <= patIdxEnd; i++) {
                if (!isDoubleStar(patDirs[i])) {
                    return false;
                }
            }
            return true;
        } else if (patIdxStart > patIdxEnd) {
            return false;
        }
        while (patIdxStart <= patIdxEnd && strIdxStart <= strIdxEnd) {
            char[] patDir = patDirs[patIdxEnd];
            if (isDoubleStar(patDir)) {
                break;
            }
            if (!match(patDir, strDirs[strIdxEnd])) {
                return false;
            }
            patIdxEnd--;
            strIdxEnd--;
        }
        if (strIdxStart > strIdxEnd) {
            for (int i = patIdxStart; i <= patIdxEnd; i++) {
                if (!isDoubleStar(patDirs[i])) {
                    return false;
                }
            }
            return true;
        }
        while (patIdxStart != patIdxEnd && strIdxStart <= strIdxEnd) {
            int patIdxTmp = -1;
            for (int i = patIdxStart + 1; i <= patIdxEnd; i++) {
                if (isDoubleStar(patDirs[i])) {
                    patIdxTmp = i;
                    break;
                }
            }
            if (patIdxTmp == patIdxStart + 1) {
                patIdxStart++;
                continue;
            }
            int patLength = (patIdxTmp - patIdxStart - 1);
            int strLength = (strIdxEnd - strIdxStart + 1);
            int foundIdx = -1;
            strLoop:
            for (int i = 0; i <= strLength - patLength; i++) {
                for (int j = 0; j < patLength; j++) {
                    char[] subPat = patDirs[patIdxStart + j + 1];
                    char[] subStr = strDirs[strIdxStart + i + j];
                    if (!match(subPat, subStr)) {
                        continue strLoop;
                    }
                }
                foundIdx = strIdxStart + i;
                break;
            }
            if (foundIdx == -1) {
                return false;
            }
            patIdxStart = patIdxTmp;
            strIdxStart = foundIdx + patLength;
        }
        for (int i = patIdxStart; i <= patIdxEnd; i++) {
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

    /** {@code SelectorUtils.match(char[], char[], boolean)}, case-sensitive. */
    private static boolean match(char[] patArr, char[] strArr) {
        int patIdxStart = 0;
        int patIdxEnd = patArr.length - 1;
        int strIdxStart = 0;
        int strIdxEnd = strArr.length - 1;
        char ch;
        boolean containsStar = false;
        for (char aPatArr : patArr) {
            if (aPatArr == '*') {
                containsStar = true;
                break;
            }
        }
        if (!containsStar) {
            if (patIdxEnd != strIdxEnd) {
                return false;
            }
            for (int i = 0; i <= patIdxEnd; i++) {
                ch = patArr[i];
                if (ch != '?' && ch != strArr[i]) {
                    return false;
                }
            }
            return true;
        }
        if (patIdxEnd == 0) {
            return true;
        }
        while ((ch = patArr[patIdxStart]) != '*' && strIdxStart <= strIdxEnd) {
            if (ch != '?' && ch != strArr[strIdxStart]) {
                return false;
            }
            patIdxStart++;
            strIdxStart++;
        }
        if (strIdxStart > strIdxEnd) {
            for (int i = patIdxStart; i <= patIdxEnd; i++) {
                if (patArr[i] != '*') {
                    return false;
                }
            }
            return true;
        }
        while ((ch = patArr[patIdxEnd]) != '*' && strIdxStart <= strIdxEnd) {
            if (ch != '?' && ch != strArr[strIdxEnd]) {
                return false;
            }
            patIdxEnd--;
            strIdxEnd--;
        }
        if (strIdxStart > strIdxEnd) {
            for (int i = patIdxStart; i <= patIdxEnd; i++) {
                if (patArr[i] != '*') {
                    return false;
                }
            }
            return true;
        }
        while (patIdxStart != patIdxEnd && strIdxStart <= strIdxEnd) {
            int patIdxTmp = -1;
            for (int i = patIdxStart + 1; i <= patIdxEnd; i++) {
                if (patArr[i] == '*') {
                    patIdxTmp = i;
                    break;
                }
            }
            if (patIdxTmp == patIdxStart + 1) {
                patIdxStart++;
                continue;
            }
            int patLength = (patIdxTmp - patIdxStart - 1);
            int strLength = (strIdxEnd - strIdxStart + 1);
            int foundIdx = -1;
            strLoop:
            for (int i = 0; i <= strLength - patLength; i++) {
                for (int j = 0; j < patLength; j++) {
                    ch = patArr[patIdxStart + j + 1];
                    if (ch != '?' && ch != strArr[strIdxStart + i + j]) {
                        continue strLoop;
                    }
                }
                foundIdx = strIdxStart + i;
                break;
            }
            if (foundIdx == -1) {
                return false;
            }
            patIdxStart = patIdxTmp;
            strIdxStart = foundIdx + patLength;
        }
        for (int i = patIdxStart; i <= patIdxEnd; i++) {
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
