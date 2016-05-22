package com.uriio.beacons.api;

import com.google.gson.annotations.SerializedName;

/**
 * Created on 4/29/2016.
 */
public class IssueUrls {
    /**
     * Outgoing API Key
     */
    @SerializedName("apiKey")
    private String apiKey;

    @SerializedName("token")
    private String token;

    @SerializedName("ttl")
    private long ttl;

    @SerializedName("num")
    private long num;

    public IssueUrls(String apiKey, String urlToken, int ttl, int numToIssue) {
        this.apiKey = apiKey;
        this.token = urlToken;
        this.ttl = ttl;
        this.num = numToIssue;
    }
}