package io.github.graphmaquina.maven;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/**
 * A processor path, searched for one class file as the processor class loader of javac searches it. That loader is a
 * {@link URLClassLoader} over the entries of the path, which runs in the JVM javac runs in and reads a multi-release
 * jar for the release of that JVM. A loader made here would read such a jar for the release of the JVM running Maven
 * instead, so this search reads it for a release it is given.
 */
final class ProcessorPath {

    /**
     * The release read where the release of javac's JDK is unknown: above every release, so no versioned entry is
     * left out.
     */
    private static final Runtime.Version ABOVE_EVERY_RELEASE =
        Runtime.Version.parse(Integer.toString(Integer.MAX_VALUE));

    private ProcessorPath() {
    }

    /**
     * Whether {@code entries} reach {@code file} for the release {@code feature}. A {@link URLClassLoader} over the
     * entries, in their order, reaches what the processor class loader of javac reaches: the entries, and the jars
     * and directories the {@code Class-Path} of a jar's manifest names, resolved against the URL of that jar. A
     * directory it reaches holds the file when the file is below it. A jar holds the file when reading it for that
     * release, as a multi-release jar when its manifest declares it one, finds the file. The jars read are those in
     * which the loader finds the file under its own name, as it does in every jar that holds the file outside
     * {@code META-INF/versions/}, and those in which it finds {@code META-INF/MANIFEST.MF}, under which name the
     * {@code jar} tool writes the manifest a multi-release jar needs. A jar that cannot be opened holds nothing.
     *
     * <p>Where the release is unknown, a multi-release jar is read for a release above every release, so it holds
     * the file under any of its versioned entries: the check then refuses no path that a javac of some release could
     * take, and a javac of an older release may still fail on it later.</p>
     *
     * @param entries the entries of the path, in their order, each resolved where javac runs
     * @param file the class file, as its path below an entry, such as {@code p/Probe.class}
     * @param feature the feature release of the JDK that javac belongs to, {@code 0} when it is unknown
     * @return {@code true} if a directory or a jar the path reaches holds the file for that release
     */
    static boolean reaches(List<Path> entries, String file, int feature) {
        Runtime.Version release = feature > 0 ? Runtime.Version.parse(Integer.toString(feature)) : ABOVE_EVERY_RELEASE;
        List<URL> urls = new ArrayList<>();
        for (Path entry : entries) {
            try {
                urls.add(entry.toUri().toURL());
            } catch (MalformedURLException unusable) {
                // javac leaves out an entry it cannot name.
            }
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null);
        try {
            for (String name : List.of(file, JarFile.MANIFEST_NAME)) {
                Enumeration<URL> found = loader.findResources(name);
                while (found.hasMoreElements()) {
                    URL resource = found.nextElement();
                    boolean inJar = "jar".equals(resource.getProtocol());
                    if (inJar ? holds(resource, file, release) : name.equals(file)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException unreadable) {
            // findResources declares this failure; its implementations in Java 21 and 25 never throw it.
            return false;
        } finally {
            try {
                loader.close();
            } catch (IOException unclosable) {
                // The lookup has answered; a jar the loader could not close is left to the JVM.
            }
        }
    }

    /**
     * Whether the jar that {@code resource} is an entry of holds {@code file} for {@code release}, read as a
     * multi-release jar when its manifest declares it one. A jar that cannot be opened holds nothing.
     */
    private static boolean holds(URL resource, String file, Runtime.Version release) {
        try {
            URL jar = ((JarURLConnection) resource.openConnection()).getJarFileURL();
            try (JarFile read = new JarFile(fileOf(jar), true, ZipFile.OPEN_READ, release)) {
                return read.getJarEntry(file) != null;
            }
        } catch (IOException | URISyntaxException | IllegalArgumentException unreadable) {
            return false;
        }
    }

    /**
     * The file the URL of a jar the loader reaches names. The loader resolves a name the {@code Class-Path} of a
     * manifest gives against the URL of that jar as a URL, which keeps the name as the manifest spells it, so it may
     * hold a character a URI does not allow, such as {@code [}, {@code {} or {@code ^}. The loader then opens the file
     * its URL names with the {@code %} escapes decoded, and so does this.
     */
    private static File fileOf(URL jar) throws URISyntaxException {
        URI uri;
        try {
            uri = jar.toURI();
        } catch (URISyntaxException notQuoted) {
            String file = URLDecoder.decode(jar.getFile().replace("+", "%2B"), StandardCharsets.UTF_8);
            uri = new URI(jar.getProtocol(), jar.getHost(), file, null);
        }
        return Path.of(uri).toFile();
    }
}
