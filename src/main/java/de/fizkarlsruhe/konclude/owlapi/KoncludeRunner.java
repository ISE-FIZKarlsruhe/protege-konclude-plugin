package de.fizkarlsruhe.konclude.owlapi;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.reasoner.ReasonerInternalException;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.semanticweb.owlapi.reasoner.TimeOutException;

/** Runs {@code Konclude materialize} on a set of axioms and parses the result. */
final class KoncludeRunner {

    private final KoncludeSettings settings;
    private final OWLDataFactory df;
    /** Konclude processes started by all runners (diagnostics). */
    static final java.util.concurrent.atomic.AtomicLong TOTAL_RUNS = new java.util.concurrent.atomic.AtomicLong();

    private final AtomicInteger counter = new AtomicInteger();
    private File workDirectory;
    private volatile Process process;
    private volatile boolean interrupted;

    KoncludeRunner(KoncludeSettings settings, OWLDataFactory df) {
        this.settings = settings;
        this.df = df;
    }

    Materialization materialize(Collection<OWLAxiom> axioms, long timeoutMillis) {
        if (settings.getCommand().isEmpty()) {
            throw new ReasonerInternalException("Konclude executable not configured. Set it in the Konclude "
                    + "preferences, via the system property '" + KoncludeSettings.PROPERTY_PATH
                    + "' or the environment variable '" + KoncludeSettings.ENV_PATH + "'.");
        }
        interrupted = false;
        int n = counter.incrementAndGet();
        TOTAL_RUNS.incrementAndGet();
        File dir = workDirectory();
        File input = new File(dir, "input-" + n + ".ofn");
        File output = new File(dir, "output-" + n + ".owl.xml");
        File log = new File(dir, "konclude-" + n + ".log");
        try {
            write(axioms, input);
            List<String> cmd = new ArrayList<>(settings.getCommand());
            cmd.add("materialize");
            cmd.add("-w");
            cmd.add(settings.getThreads());
            cmd.add("-i");
            cmd.add(input.getAbsolutePath());
            cmd.add("-o");
            cmd.add(output.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log);
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new ReasonerInternalException("Cannot start Konclude (" + String.join(" ", cmd) + "): "
                        + e.getMessage(), e);
            }
            boolean finished = waitFor(process, timeoutMillis);
            if (!finished) {
                process.destroyForcibly();
                throw new TimeOutException();
            }
            if (interrupted) {
                throw new ReasonerInterruptedException("Konclude was interrupted");
            }
            int exit = process.exitValue();
            if (exit != 0 || !output.isFile() || output.length() == 0) {
                throw new ReasonerInternalException(failureMessage(cmd, exit, log, output));
            }
            try {
                return Materialization.parse(output, df);
            } catch (IOException e) {
                throw new ReasonerInternalException(e.getMessage() + "\n" + tail(log), e);
            }
        } finally {
            process = null;
            if (!settings.isKeepTempFiles()) {
                input.delete();
                output.delete();
                log.delete();
            }
        }
    }

    private boolean waitFor(Process p, long timeoutMillis) {
        try {
            if (timeoutMillis <= 0 || timeoutMillis == Long.MAX_VALUE) {
                p.waitFor();
                return true;
            }
            return p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ReasonerInterruptedException(e);
        }
    }

    /** Kills a running Konclude process, if any. */
    void interrupt() {
        interrupted = true;
        Process p = process;
        if (p != null) {
            p.destroyForcibly();
        }
    }

    private void write(Collection<OWLAxiom> axioms, File file) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file.toPath()))) {
            OWLOntology ontology = manager.createOntology();
            manager.addAxioms(ontology, new java.util.HashSet<OWLAxiom>(axioms));
            manager.saveOntology(ontology, new FunctionalSyntaxDocumentFormat(), out);
        } catch (OWLOntologyCreationException | OWLOntologyStorageException | IOException e) {
            throw new ReasonerInternalException("Cannot write Konclude input " + file + ": " + e.getMessage(), e);
        }
    }

    private synchronized File workDirectory() {
        if (workDirectory == null) {
            try {
                File base = settings.getTempDirectory();
                if (base != null) {
                    base.mkdirs();
                    workDirectory = Files.createTempDirectory(base.toPath(), "konclude-protege-").toFile();
                } else {
                    workDirectory = Files.createTempDirectory("konclude-protege-").toFile();
                }
            } catch (IOException e) {
                throw new ReasonerInternalException("Cannot create temp directory: " + e.getMessage(), e);
            }
        }
        return workDirectory;
    }

    File getWorkDirectory() {
        return workDirectory;
    }

    void dispose() {
        interrupt();
        File dir = workDirectory;
        if (dir != null && !settings.isKeepTempFiles()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    private static String failureMessage(List<String> cmd, int exit, File log, File output) {
        String commandLine = String.join(" ", cmd);
        if (printedUsage(log)) {
            return "Konclude printed its usage help instead of running 'materialize', so the command line was not "
                    + "understood:\n  " + commandLine + "\n"
                    + "Check the Konclude command in File > Preferences > Konclude. If it starts a Docker image whose "
                    + "entrypoint is already Konclude (e.g. konclude/konclude), do not add 'Konclude' after the image "
                    + "name. 'materialize' needs Konclude v0.7.0 or later.";
        }
        if (exit == 0) {
            return "Konclude finished but wrote no result to " + output + ".\n  " + commandLine + "\n"
                    + "If Konclude runs in Docker, the temp directory must be mounted at the same path inside the "
                    + "container (-v <dir>:<dir>) and set as 'Temp directory' in the preferences.\n" + tail(log);
        }
        return "Konclude failed (exit code " + exit + "):\n  " + commandLine + "\n" + tail(log);
    }

    private static boolean printedUsage(File log) {
        try {
            for (String line : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("Available commands:") || line.contains(">> Usage: 'Konclude'")) {
                    return true;
                }
            }
        } catch (IOException e) {
            // no log: fall through
        }
        return false;
    }

    private static String tail(File log) {
        try {
            List<String> lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
            List<String> relevant = new ArrayList<>();
            for (String l : lines) {
                if (l.contains("{error}") || l.contains("{warning}")) {
                    relevant.add(l);
                }
            }
            List<String> source = relevant.isEmpty() ? lines : relevant;
            return String.join("\n", source.subList(Math.max(0, source.size() - 15), source.size()));
        } catch (IOException e) {
            return "(no Konclude log)";
        }
    }
}
