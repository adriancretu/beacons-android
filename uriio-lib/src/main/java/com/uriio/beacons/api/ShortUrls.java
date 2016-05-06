package com.uriio.beacons.api;

import com.google.gson.annotations.SerializedName;

/**
 * Created on 5/04/2016.
 */
public class ShortUrls {
    @SerializedName("items")
    private ShortUrl[] items;

    public ShortUrl[] getItems() {
        return items;
    }
}
