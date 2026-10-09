package com.havalh6.viewer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LyricsFetcherTest {
    @Test
    public void titleDropsFeatAndRemasterSuffixes() {
        assertEquals("Song", LyricsFetcher.cleanTitle("Song (feat. Someone)"));
        assertEquals("Song", LyricsFetcher.cleanTitle("Song - 2011 Remaster"));
        assertEquals("Song", LyricsFetcher.cleanTitle("Song [Live]"));
    }

    @Test
    public void titleKeepsPlainDashedNames() {
        assertEquals("Jack - Daniels", LyricsFetcher.cleanTitle("Jack - Daniels"));
    }

    @Test
    public void titleThatIsAllBracketsIsNotEmptied() {
        assertEquals("(Untitled)", LyricsFetcher.cleanTitle("(Untitled)"));
    }

    @Test
    public void artistDropsTopicAndCoArtists() {
        assertEquals("Tom Jobim", LyricsFetcher.cleanArtist("Tom Jobim - Topic"));
        assertEquals("Anitta", LyricsFetcher.cleanArtist("Anitta, Ludmilla"));
    }
}
