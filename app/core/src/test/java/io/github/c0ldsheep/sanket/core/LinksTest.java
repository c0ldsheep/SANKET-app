package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LinksTest {
    @Test
    public void findsTheLinkInsideASentence() {
        assertEquals("https://example.com/file.pdf",
                Links.firstHttps("Download it here: https://example.com/file.pdf. Thanks!"));
        assertEquals("https://example.com/a?b=1&c=2", Links.firstHttps("(https://example.com/a?b=1&c=2)"));
        assertEquals("https://example.com/notes", Links.firstHttps("फ़ाइल यहाँ है https://example.com/notes।"));
    }

    @Test
    public void keepsBracketsThatBelongToTheLink() {
        assertEquals("https://en.wikipedia.org/wiki/Lift_(elevator)",
                Links.firstHttps("see https://en.wikipedia.org/wiki/Lift_(elevator)"));
        assertEquals("https://example.com/x", Links.firstHttps("[https://example.com/x]"));
    }

    @Test
    public void refusesTextWithoutASecureLink() {
        assertNull(Links.firstHttps(null));
        assertNull(Links.firstHttps("http://example.com/file.zip"));
        assertNull(Links.firstHttps("no link at all"));
        assertNull(Links.firstHttps("https://."));
    }
}
