package de.fizkarlsruhe.konclude.owlapi;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * How to launch Konclude.
 *
 * <p>The command is a list of tokens placed in front of the Konclude arguments, e.g.
 * {@code /opt/konclude/Binaries/Konclude} or
 * {@code docker run --rm -v /tmp:/tmp konclude/konclude} (an image whose entrypoint is Konclude).
 *
 * <p>Entailment checks of reasoners that have not classified (the explanation workbench creates
 * many of those) can go to a Konclude OWLlink server instead of starting a process per check.
 */
public final class KoncludeSettings {

    public static final String ENV_PATH = "KONCLUDE_PATH";
    public static final String PROPERTY_PATH = "konclude.path";

    private final List<String> command;
    private final String threads;
    private final File tempDirectory;
    private final boolean keepTempFiles;
    private final OwlLinkMode owlLinkMode;
    private final String owlLinkUrl;

    /** How entailment checks (explanations) reach Konclude. */
    public enum OwlLinkMode {
        /** one Konclude process per check */
        OFF,
        /** start an OWLlink server with the configured command (default) */
        AUTO,
        /** use the OWLlink server at {@link KoncludeSettings#getOwlLinkUrl()} */
        URL
    }

    public KoncludeSettings(List<String> command, String threads, File tempDirectory, boolean keepTempFiles) {
        this(command, threads, tempDirectory, keepTempFiles, OwlLinkMode.AUTO, null);
    }

    public KoncludeSettings(List<String> command, String threads, File tempDirectory, boolean keepTempFiles,
            OwlLinkMode owlLinkMode, String owlLinkUrl) {
        this.command = Collections.unmodifiableList(new ArrayList<>(command));
        this.threads = threads == null || threads.trim().isEmpty() ? "AUTO" : threads.trim();
        this.tempDirectory = tempDirectory;
        this.keepTempFiles = keepTempFiles;
        this.owlLinkMode = owlLinkMode == null ? OwlLinkMode.AUTO : owlLinkMode;
        this.owlLinkUrl = owlLinkUrl == null ? "" : owlLinkUrl.trim();
    }

    /** Settings from {@code konclude.path} / {@code KONCLUDE_PATH} or a {@code Konclude} binary on the PATH. */
    public static KoncludeSettings detect() {
        String exe = detectExecutable();
        return new KoncludeSettings(exe == null ? Collections.<String>emptyList() : Collections.singletonList(exe),
                "AUTO", null, false);
    }

    public static String detectExecutable() {
        String configured = System.getProperty(PROPERTY_PATH);
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv(ENV_PATH);
        }
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[] names = windows ? new String[] {"Konclude.exe", "konclude.exe"} : new String[] {"Konclude", "konclude"};
        for (String dir : path.split(File.pathSeparator)) {
            for (String name : names) {
                File f = new File(dir, name);
                if (f.isFile() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /** Splits a command line into tokens; double or single quotes group tokens containing spaces. */
    public static List<String> tokenize(String commandLine) {
        List<String> tokens = new ArrayList<>();
        if (commandLine == null) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (char c : commandLine.trim().toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    public List<String> getCommand() {
        return command;
    }

    public String getThreads() {
        return threads;
    }

    /** Directory for exchange files, or {@code null} for the system temp directory. */
    public File getTempDirectory() {
        return tempDirectory;
    }

    public boolean isKeepTempFiles() {
        return keepTempFiles;
    }

    public OwlLinkMode getOwlLinkMode() {
        return owlLinkMode;
    }

    public String getOwlLinkUrl() {
        return owlLinkUrl;
    }

    @Override
    public String toString() {
        return "KoncludeSettings" + command + " -w " + threads;
    }
}
