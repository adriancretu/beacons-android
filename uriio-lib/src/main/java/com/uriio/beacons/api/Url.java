package com.uriio.beacons.api;

import android.util.Base64;

import com.google.gson.annotations.SerializedName;

/**
 * Created on 4/29/2016.
 */
public class Url {
    @SerializedName("id")
    private long id;

    @SerializedName("url")
    private String url;

    @SerializedName("token")
    private String token;

    @SerializedName("publicKey")
    private String publicKey;

    /** Total issued URLs **/
    @SerializedName("numIssued")
    private long numIssued;

    @SerializedName("created")
    private String created;

    @SerializedName("deleted")
    private String deleted;

    @SerializedName("hits")
    private long hits;

    public Url(String url, byte[] publicKey) {
        this.url = url;
        this.publicKey = Base64.encodeToString(publicKey, Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
    }

    public String getToken() {
        return token;
    }

    public long getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }
}