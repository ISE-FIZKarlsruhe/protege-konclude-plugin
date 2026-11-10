package de.fizkarlsruhe.konclude.protege;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.ui.preferences.OWLPreferencesPanel;

import de.fizkarlsruhe.konclude.owlapi.KoncludeSelfTest;
import de.fizkarlsruhe.konclude.owlapi.KoncludeSettings;

/** File &gt; Preferences &gt; Konclude. */
public class KoncludePreferencesPanel extends OWLPreferencesPanel {

    private static final long serialVersionUID = 1L;

    private final JTextField command = new JTextField(40);
    private final JTextField threads = new JTextField(8);
    private final JTextField tempDir = new JTextField(40);
    private final JCheckBox keepFiles = new JCheckBox("Keep exchange files (input, output, log) for debugging");
    private final JLabel status = new JLabel(" ");
    private final javax.swing.JComboBox<String> owlLinkMode = new javax.swing.JComboBox<>(new String[] {
        "OWLlink server, started automatically (recommended)", "OWLlink server at URL", "One Konclude run per check"});
    private final JTextField owlLinkUrl = new JTextField(40);
    private static final KoncludeSettings.OwlLinkMode[] MODES = {KoncludeSettings.OwlLinkMode.AUTO,
        KoncludeSettings.OwlLinkMode.URL, KoncludeSettings.OwlLinkMode.OFF};

    @Override
    public void initialise() throws Exception {
        setLayout(new BorderLayout());
        Preferences p = KoncludePreferences.store();
        command.setText(p.getString(KoncludePreferences.COMMAND, ""));
        threads.setText(p.getString(KoncludePreferences.THREADS, "AUTO"));
        tempDir.setText(p.getString(KoncludePreferences.TEMP_DIR, ""));
        keepFiles.setSelected(p.getBoolean(KoncludePreferences.KEEP_FILES, false));
        owlLinkMode.setSelectedIndex(java.util.Arrays.asList(MODES)
                .indexOf(KoncludePreferences.owlLinkMode(p.getString(KoncludePreferences.OWLLINK_MODE, "AUTO"))));
        owlLinkUrl.setText(p.getString(KoncludePreferences.OWLLINK_URL, ""));
        owlLinkUrl.setToolTipText("e.g. http://localhost:8080/ for: docker run --rm -p 8080:8080 konclude/konclude owllinkserver");
        owlLinkMode.addActionListener(e -> owlLinkUrl.setEnabled(selectedMode() == KoncludeSettings.OwlLinkMode.URL));
        owlLinkUrl.setEnabled(selectedMode() == KoncludeSettings.OwlLinkMode.URL);
        String detected = KoncludeSettings.detectExecutable();
        command.setToolTipText("Path to the Konclude executable, optionally followed by arguments placed before the "
                + "Konclude command (e.g. a docker run prefix). Empty = auto-detect"
                + (detected == null ? "" : " (" + detected + ")"));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Konclude reasoner"));
        int row = 0;
        addRow(form, row++, "Konclude command", command, browse(command, false));
        addRow(form, row++, "Threads (-w)", threads, new JLabel("number or AUTO"));
        addRow(form, row++, "Temp directory", tempDir, browse(tempDir, true));
        GridBagConstraints c = constraints(1, row++);
        c.gridwidth = 2;
        form.add(keepFiles, c);
        addRow(form, row++, "Explanations", owlLinkMode, null);
        addRow(form, row++, "OWLlink server URL", owlLinkUrl, null);
        JButton test = new JButton("Test");
        test.addActionListener(e -> test());
        addRow(form, row++, "", status, test);
        // fixed width so the hint wraps instead of widening the preferences dialog
        JLabel hint = new JLabel("<html><div style='width:420px'>Leave the command empty to use the system property "
                + "<tt>konclude.path</tt>, the environment variable <tt>KONCLUDE_PATH</tt> or <tt>Konclude</tt> on "
                + "the PATH. Changes apply when the reasoner is started next."
                + (detected == null ? "<br>No Konclude executable found automatically."
                        : "<br>Found: <tt>" + escape(shorten(detected)) + "</tt>")
                + "</div></html>");
        c = constraints(0, row);
        c.gridwidth = 3;
        form.add(hint, c);
        add(form, BorderLayout.NORTH);
    }

    private static String shorten(String path) {
        return path.length() <= 70 ? path : "…" + path.substring(path.length() - 69);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.insets = new Insets(3, 5, 3, 5);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static void addRow(JPanel form, int row, String label, java.awt.Component field, java.awt.Component extra) {
        form.add(new JLabel(label), constraints(0, row));
        GridBagConstraints c = constraints(1, row);
        c.weightx = 1;
        form.add(field, c);
        if (extra != null) {
            form.add(extra, constraints(2, row));
        }
    }

    private JButton browse(JTextField target, boolean directory) {
        JButton b = new JButton("Browse…");
        b.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String path = chooser.getSelectedFile().getAbsolutePath();
                target.setText(directory ? path : KoncludePreferences.quote(path));
            }
        });
        return b;
    }

    /**
     * Reads the version ({@code <command> -h}) and then runs a tiny reasoning job with the settings as
     * currently entered, the same way the reasoner does.
     */
    private void test() {
        String cmdLine = command.getText().trim().isEmpty() ? KoncludePreferences.command() : command.getText();
        List<String> base = KoncludeSettings.tokenize(cmdLine);
        if (base.isEmpty()) {
            status.setText("No Konclude executable configured or found.");
            return;
        }
        String dir = tempDir.getText().trim();
        KoncludeSettings settings = new KoncludeSettings(base, threads.getText(), dir.isEmpty() ? null : new File(dir),
                keepFiles.isSelected(), selectedMode(), owlLinkUrl.getText());
        List<String> help = new ArrayList<>(base);
        help.add("-h");
        status.setText("Running…");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                String version = version(help);
                String result = "OK: Konclude " + version + ", " + KoncludeSelfTest.run(settings);
                String owlLink = KoncludeSelfTest.runOwlLink(settings);
                return owlLink == null ? result : "<html>" + result + "<br>" + owlLink + "</html>";
            }

            @Override
            protected void done() {
                try {
                    status.setText(get());
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    status.setText("Failed (see message)");
                    JTextArea text = new JTextArea(cause.getMessage(), 12, 70);
                    text.setLineWrap(true);
                    text.setWrapStyleWord(true);
                    text.setEditable(false);
                    JOptionPane.showMessageDialog(KoncludePreferencesPanel.this, new JScrollPane(text),
                            "Konclude test failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** Runs {@code <command> -h} and returns the version token, or throws if Konclude cannot be started. */
    private static String version(List<String> help) throws Exception {
        Process p;
        try {
            p = new ProcessBuilder(help).redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot start " + String.join(" ", help) + ":\n" + e.getMessage(), e);
        }
        String version = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("Version (\\S+)").matcher(line);
                if (version == null && m.find()) {
                    version = m.group(1);
                }
            }
        }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        return version == null ? "(unknown version)" : version;
    }

    @Override
    public void applyChanges() {
        Preferences p = KoncludePreferences.store();
        p.putString(KoncludePreferences.COMMAND, command.getText().trim());
        p.putString(KoncludePreferences.THREADS, threads.getText().trim().isEmpty() ? "AUTO" : threads.getText().trim());
        String dir = tempDir.getText().trim();
        if (!dir.isEmpty() && !new File(dir).isDirectory()) {
            new File(dir).mkdirs();
        }
        p.putString(KoncludePreferences.TEMP_DIR, dir);
        p.putBoolean(KoncludePreferences.KEEP_FILES, keepFiles.isSelected());
        p.putString(KoncludePreferences.OWLLINK_MODE, selectedMode().name());
        p.putString(KoncludePreferences.OWLLINK_URL, owlLinkUrl.getText().trim());
    }

    private KoncludeSettings.OwlLinkMode selectedMode() {
        int i = owlLinkMode.getSelectedIndex();
        return i < 0 ? KoncludeSettings.OwlLinkMode.AUTO : MODES[i];
    }

    @Override
    public void dispose() throws Exception {
    }
}
