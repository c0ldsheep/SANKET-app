package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FileNamesTest {
    @Test
    public void readsTheServersOwnName() {
        assertEquals("report 2026.pdf", FileNames.fromDisposition("attachment; filename=\"report 2026.pdf\""));
        assertEquals("plain.zip", FileNames.fromDisposition("attachment; filename=plain.zip"));
        assertEquals("नोट्स.pdf",
                FileNames.fromDisposition("attachment; filename=\"notes.pdf\"; filename*=UTF-8''%E0%A4%A8%E0%A5%8B%E0%A4%9F%E0%A5%8D%E0%A4%B8.pdf"));
        assertEquals("a;b.txt", FileNames.fromDisposition("attachment; filename=\"a;b.txt\""));
        assertNull(FileNames.fromDisposition("inline"));
        assertNull(FileNames.fromDisposition(null));
    }

    @Test
    public void prefersTheServerNameAndFixesScriptNames() {
        assertEquals("invoice.pdf", FileNames.choose("attachment; filename=\"invoice.pdf\"", "download.php", "pdf"));
        assertEquals("download.pdf", FileNames.choose(null, "download.php", "pdf"));
        assertEquals("archive.zip", FileNames.choose(null, "archive", "zip"));
        assertEquals("song.mp3", FileNames.choose(null, "song.mp3", "mpeg"));
        assertEquals("download", FileNames.choose(null, null, null));
    }

    @Test
    public void keepsIndianScriptsAndRemovesDangerousParts() {
        assertEquals("मराठी कविता.pdf", FileNames.clean("मराठी कविता.pdf"));
        assertEquals("passwd", FileNames.clean("../../etc/passwd"));
        assertEquals("hidden", FileNames.clean(".hidden"));
        assertEquals("a_b_c.txt", FileNames.clean("a<b>c.txt"));
        assertEquals("", FileNames.clean("..."));
    }

    @Test
    public void shortensLongNamesButKeepsTheExtension() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 150; i++) b.append('x');
        String name = FileNames.clean(b + ".mp4");
        assertTrue(name.endsWith(".mp4"));
        assertEquals(FileNames.MAX_LENGTH, name.length());
    }
}
