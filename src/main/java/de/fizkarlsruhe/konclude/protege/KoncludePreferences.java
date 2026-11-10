package de.fizkarlsruhe.konclude.protege;

import java.io.File;
import java.util.List;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;

import de.fizkarlsruhe.konclude.owlapi.KoncludeSettings;

/** Konclude settings stored in the Protégé application preferences. */
final class KoncludePreferences {

    private static final String SET = "de.fizkarlsruhe.konclude";
    static final String COMMAND = "command";
    static final String THREADS = "threads";
    static final String TEMP_DIR = "tempDirectory";
    static final String KEEP_FILES = "keepTempFiles";
    static final String OWLLINK_MODE = "owlLinkMode";
    static final String OWLLINK_URL = "owlLinkUrl";

    private KoncludePreferences() {
    }

    static Preferences store() {
        return PreferencesManager.getInstance().getApplicationPreferences(SET);
    }

    /** The configured command, or the auto-detected Konclude executable if none is configured. */
    static String command() {
        String cmd = store().getString(COMMAND, "");
        if (cmd.trim().isEmpty()) {
            String detected = KoncludeSettings.detectExecutable();
            return detected == null ? "" : quote(detected);
        }
        return cmd;
    }

    static KoncludeSettings load() {
        Preferences p = store();
        List<String> command = KoncludeSettings.tokenize(command());
        String temp = p.getString(TEMP_DIR, "").trim();
        return new KoncludeSettings(command, p.getString(THREADS, "AUTO"), temp.isEmpty() ? null : new File(temp),
                p.getBoolean(KEEP_FILES, false), owlLinkMode(p.getString(OWLLINK_MODE, "AUTO")),
                p.getString(OWLLINK_URL, ""));
    }

    static KoncludeSettings.OwlLinkMode owlLinkMode(String name) {
        try {
            return KoncludeSettings.OwlLinkMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return KoncludeSettings.OwlLinkMode.AUTO;
        }
    }

    static String quote(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }
}
