package io.github.quoll.owltx;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How owltx decides what to write.
 *
 * <p>The rules are: an explicit {@code --format} wins, then the output file's
 * extension, then DLe. The last of those was returning plain DL syntax, so
 * {@code owltx doc.dle} with no output file and no format silently produced a
 * different language — no {@code @prefix} declarations, no header, {@code self}
 * where DLe requires {@code Self}, and no line breaks. Nothing tested the
 * defaulting, which is why it went unnoticed.
 */
class FormatSelectionTest {

    @Test
    void noFormatAndNoOutputFileMeansDle() {
        // The stdout case: `owltx doc.dle`.
        assertInstanceOf(DLESyntaxDocumentFormat.class, Main.resolveFormat(null, null));
    }

    @Test
    void unrecognisedOutputExtensionFallsBackToDle() {
        assertInstanceOf(DLESyntaxDocumentFormat.class, Main.resolveFormat(null, "out.wat"));
    }

    @Test
    void outputFileWithNoExtensionFallsBackToDle() {
        assertInstanceOf(DLESyntaxDocumentFormat.class, Main.resolveFormat(null, "out"));
    }

    @Test
    void plainDlIsStillReachableButOnlyOnRequest() {
        // The old default. It remains available, because rendering plain DL is a
        // legitimate thing to ask for — it just is not what DLe means.
        OWLDocumentFormat dl = Main.resolveFormat("dl", null);
        assertFalse(dl instanceof DLESyntaxDocumentFormat,
            "'dl' must be plain DL syntax, not DLe");
    }

    @Test
    void explicitFormatBeatsTheOutputExtension() {
        assertInstanceOf(TurtleDocumentFormat.class, Main.resolveFormat("turtle", "out.dle"));
    }

    @Test
    void outputExtensionIsUsedWhenNoFormatIsGiven() {
        assertInstanceOf(FunctionalSyntaxDocumentFormat.class, Main.resolveFormat(null, "out.ofn"));
        assertInstanceOf(TurtleDocumentFormat.class, Main.resolveFormat(null, "out.ttl"));
        assertInstanceOf(DLESyntaxDocumentFormat.class, Main.resolveFormat(null, "out.dle"));
    }

    @Test
    void extensionMatchingIsCaseInsensitive() {
        assertInstanceOf(TurtleDocumentFormat.class, Main.resolveFormat(null, "OUT.TTL"));
    }

    @Test
    void formatNameMatchingIsCaseInsensitive() {
        assertInstanceOf(DLESyntaxDocumentFormat.class, Main.resolveFormat("DLE", null));
    }
}
