package de.fizkarlsruhe.konclude.owlapi;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * A Konclude OWLlink server started with the configured command, shared by all reasoners with the same
 * command and stopped when the JVM exits. For {@code docker run} / {@code podman run} commands a port
 * mapping and a container name are inserted after {@code run}.
 */
final class OwlLinkServer {

    private static final Logger LOG = Logger.getLogger(OwlLinkServer.class.getName());
    private static final Map<List<String>, OwlLinkServer> SERVERS = new HashMap<>();
    private static boolean hookInstalled;

    static final String PING = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<RequestMessage xmlns=\"http://www.owllink.org/owllink#\"><GetDescription/></RequestMessage>";

    private final Process process;
    private final URL url;
    private final List<String> command;
    private final String container;
    private final File log;

    private OwlLinkServer(Process process, URL url, List<String> command, String container, File log) {
        this.process = process;
        this.url = url;
        this.command = command;
        this.container = container;
        this.log = log;
    }

    /** The OWLlink endpoint for these settings; starts (or restarts) a server in AUTO mode. */
    static URL endpoint(KoncludeSettings settings) throws IOException {
        if (settings.getOwlLinkMode() == KoncludeSettings.OwlLinkMode.URL) {
            return new URL(settings.getOwlLinkUrl());
        }
        List<String> key = new ArrayList<>(settings.getCommand());
        key.add(settings.getThreads());
        synchronized (SERVERS) {
            OwlLinkServer server = SERVERS.get(key);
            if (server == null || !server.process.isAlive()) {
                if (server != null) {
                    LOG.warning("Konclude OWLlink server stopped, restarting");
                    server.stop();
                }
                server = start(settings);
                SERVERS.put(key, server);
                installShutdownHook();
            }
            return server.url;
        }
    }

    /** Whether {@code command} starts a container ({@code docker run …} or {@code podman run …}). */
    static boolean isContainerCommand(List<String> command) {
        if (command.size() < 2) {
            return false;
        }
        String exe = new File(command.get(0)).getName().toLowerCase(java.util.Locale.ROOT);
        return (exe.equals("docker") || exe.equals("docker.exe") || exe.equals("podman") || exe.equals("podman.exe"))
                && command.indexOf("run") > 0;
    }

    /** The full server command line for {@code base} listening on {@code port}. */
    static List<String> serverCommand(List<String> base, String threads, int port, String container) {
        List<String> cmd = new ArrayList<>(base);
        if (container != null) {
            int run = cmd.indexOf("run");
            List<String> extra = new ArrayList<>();
            extra.add("-p");
            extra.add("127.0.0.1:" + port + ":" + port);
            extra.add("--name");
            extra.add(container);
            cmd.addAll(run + 1, extra);
        }
        cmd.add("owllinkserver");
        cmd.add("-p");
        cmd.add(Integer.toString(port));
        cmd.add("-w");
        cmd.add(threads);
        return cmd;
    }

    private static OwlLinkServer start(KoncludeSettings settings) throws IOException {
        if (settings.getCommand().isEmpty()) {
            throw new IOException("Konclude executable not configured");
        }
        int port = freePort();
        String container = isContainerCommand(settings.getCommand()) ? "konclude-protege-" + port : null;
        List<String> cmd = serverCommand(settings.getCommand(), settings.getThreads(), port, container);
        File log = File.createTempFile("konclude-owllink-", ".log");
        log.deleteOnExit();
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
        URL url = new URL("http://127.0.0.1:" + port + "/");
        OwlLinkServer server = new OwlLinkServer(p, url, cmd, container, log);
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(container != null ? 120 : 30);
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                String out = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
                throw new IOException("Konclude OWLlink server exited (" + String.join(" ", cmd) + "):\n"
                        + out.substring(Math.max(0, out.length() - 1500)));
            }
            if (ping(url)) {
                LOG.info("Konclude OWLlink server started: " + String.join(" ", cmd));
                return server;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                server.stop();
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while starting the Konclude OWLlink server", e);
            }
        }
        server.stop();
        throw new IOException("Konclude OWLlink server did not answer on " + url + " (" + String.join(" ", cmd) + ")");
    }

    static boolean ping(URL url) {
        try {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            try (OutputStream out = c.getOutputStream()) {
                out.write(PING.getBytes(StandardCharsets.UTF_8));
            }
            boolean ok = c.getResponseCode() == 200;
            c.getInputStream().close();
            return ok;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void stop() {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        if (container != null) {
            try {
                // killing the docker client does not always stop the container
                new ProcessBuilder(command.get(0), "rm", "-f", container).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(20, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException e) {
                LOG.warning("Cannot remove container " + container + ": " + e.getMessage());
            }
        }
        log.delete();
    }

    private static void installShutdownHook() {
        if (!hookInstalled) {
            hookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(OwlLinkServer::stopAll, "konclude-owllink-shutdown"));
        }
    }

    /** Stops all servers started by this plugin. */
    static void stopAll() {
        synchronized (SERVERS) {
            for (OwlLinkServer s : SERVERS.values()) {
                s.stop();
            }
            SERVERS.clear();
        }
    }
}
