package com.sismics.util.jpa;

import com.google.common.io.CharStreams;
import com.sismics.docs.core.util.ConfigUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The migration script matcher must select scripts by their ASCII resource names regardless of the
 * host's default locale.
 *
 * <p>{@link DbOpenHelper#executeAllScript(int)} renders the version into a regex that is matched
 * against resource names such as {@code dbupdate-059-0.sql}. On a host whose default locale uses a
 * non-ASCII numbering system (Eastern Arabic-Indic digits on {@code ar-EG}, {@code fa-IR}, and
 * others) an unlocalised {@code String.format("%03d", …)} renders non-ASCII digits, the regex
 * matches nothing, and the method returns NORMALLY having executed no script at all — the
 * application then runs against an unmigrated schema with no exception and no error log. These
 * tests pin the matcher to ASCII digits.
 */
public class TestDbOpenHelperLocale {
    /**
     * A locale whose default numbering system is Eastern Arabic-Indic, so {@code %d} renders
     * non-ASCII digits. Arabic-locale hosts are ordinary environments, not a lab construction.
     */
    private static final Locale NON_ASCII_DIGIT_LOCALE = Locale.forLanguageTag("ar-EG");

    /**
     * A version known to have exactly one migration script on the classpath.
     */
    private static final int SAMPLE_VERSION = 59;

    private static final String SAMPLE_SCRIPT = "dbupdate-059-0.sql";

    private Locale previousDefault;

    @BeforeEach
    public void setUp() {
        previousDefault = Locale.getDefault();
        Locale.setDefault(NON_ASCII_DIGIT_LOCALE);
    }

    @AfterEach
    public void tearDown() {
        // Locale.setDefault is JVM-global: restore it so a hostile default cannot leak into any
        // sibling test, whatever this test did or threw.
        Locale.setDefault(previousDefault);
    }

    /**
     * The scripts {@link DbOpenHelper#executeAllScript(int)} actually selects for a version, captured
     * by their CONTENT. The real matcher runs; only the SQL execution is stubbed out, so this drives
     * the production resource-matching path rather than re-implementing it.
     */
    private List<String> scriptsSelectedFor(int version) throws Exception {
        List<String> executed = new ArrayList<>();
        DbOpenHelper helper = new DbOpenHelper(null) {
            @Override
            public void onCreate() {
                // Not exercised: this test drives executeAllScript directly.
            }

            @Override
            public void onUpgrade(int oldVersion, int newVersion) {
                // Not exercised: this test drives executeAllScript directly.
            }

            @Override
            void executeScript(InputStream inputScript) throws IOException {
                executed.add(read(inputScript));
            }
        };
        helper.executeAllScript(version);
        return executed;
    }

    private static String read(InputStream inputStream) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return CharStreams.toString(reader);
        }
    }

    private String scriptContent(String fileName) throws IOException {
        return read(getClass().getResourceAsStream("/db/update/" + fileName));
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(c -> c < 128);
    }

    /**
     * Premise check: the chosen locale must really render non-ASCII digits on this JDK, otherwise
     * every other test here would pass vacuously. If a JDK release changes ar-EG's default numbering
     * system, this fails loudly and a different locale must be chosen.
     */
    @Test
    public void chosenLocaleReallyRendersNonAsciiDigits() {
        Assertions.assertFalse(isAscii(String.format("%03d", SAMPLE_VERSION)),
                "premise: the default locale must render non-ASCII digits for this test to mean anything");
    }

    /**
     * THE DEFECT: on a non-ASCII-digit host the matcher must still find the version's scripts.
     * Before the fix this finds NOTHING and executeAllScript returns normally — a silently
     * unmigrated schema.
     */
    @Test
    public void scriptsAreFoundUnderANonAsciiDigitLocale() throws Exception {
        Assertions.assertEquals(List.of(scriptContent(SAMPLE_SCRIPT)), scriptsSelectedFor(SAMPLE_VERSION),
                "the version's migration script must be selected on a non-ASCII-digit host");
    }

    /**
     * The ASCII host must be completely unaffected: the same version selects the same single script.
     * This is the no-regression half of the fix and passes both before and after it.
     */
    @Test
    public void asciiHostSelectsTheSameScript() throws Exception {
        Locale.setDefault(Locale.US);
        Assertions.assertEquals(List.of(scriptContent(SAMPLE_SCRIPT)), scriptsSelectedFor(SAMPLE_VERSION),
                "an ASCII-digit host must select exactly the same script as before");
    }

    /**
     * The loud-failure guard for the next instance of this class: EVERY version up to the configured
     * db.version must select at least one script, and must select the SAME scripts on a hostile
     * locale as on an ASCII one. A future unlocalised format — or a version bumped without its
     * migration script — fails here in CI instead of silently booting on an unmigrated schema.
     */
    @Test
    public void everyVersionSelectsTheSameNonEmptyScriptsOnBothLocales() throws Exception {
        int currentVersion = Integer.parseInt(ConfigUtil.getConfigBundle().getString("db.version"));
        Assertions.assertTrue(currentVersion > 0, "db.version must be readable");

        for (int version = 1; version <= currentVersion; version++) {
            Locale.setDefault(Locale.US);
            List<String> ascii = scriptsSelectedFor(version);
            Assertions.assertFalse(ascii.isEmpty(), "no migration script found for version " + version);

            Locale.setDefault(NON_ASCII_DIGIT_LOCALE);
            Assertions.assertEquals(ascii, scriptsSelectedFor(version),
                    "version " + version + " must select the same scripts on a non-ASCII-digit host");
        }
    }
}
