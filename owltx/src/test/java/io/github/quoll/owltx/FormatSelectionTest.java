package io.github.quoll.owltx;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.formats.DLESyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.DLSyntaxDocumentFormat;
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
        // legitimate thing to ask for — it just is not what DLe means. Asserted
        // as the concrete class: `not DLe` would be satisfied by any format at all.
        assertInstanceOf(DLSyntaxDocumentFormat.class, Main.resolveFormat("dl", null));
    }

    @Test
    void eachCallGetsItsOwnFormatInstance() {
        // main() writes the source document's prefixes into the format it is
        // given. If that were the lookup table's own instance, the prefixes would
        // outlive the conversion and a second one in the same JVM would render
        // short names against the first document's namespaces.
        assertNotSame(Main.resolveFormat("turtle", null), Main.resolveFormat("turtle", null));
        assertNotSame(Main.resolveFormat(null, "a.dle"), Main.resolveFormat(null, "b.dle"));
        assertNotSame(Main.resolveFormat(null, null), Main.resolveFormat(null, null));
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
