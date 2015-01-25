package com.kabouzeid.materialmusic.comparator;

import com.kabouzeid.materialmusic.model.Album;

import java.util.Comparator;

/**
 * Created by karim on 25.11.14.
 */
public class AlbumAlphabeticComparator implements Comparator<Album> {
    @Override
    public int compare(Album lhs, Album rhs) {
        return lhs.title.trim().compareToIgnoreCase(rhs.title.trim());
    }
}
