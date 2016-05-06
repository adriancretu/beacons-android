package com.uriio.beacons.api;

import android.text.format.Time;

import com.google.gson.annotations.SerializedName;

import java.util.Date;

/**
 * Created on 4/29/2016.
 */
public class IssueUrls {
    @SerializedName("token")
    private String token;

    @SerializedName("ttl")
    private long ttl;

    @SerializedName("num")
    private long num;

    public IssueUrls(String urlToken, int ttl, int numToIssue) {
        this.token = urlToken;
        this.ttl = ttl;
        this.num = numToIssue;
    }
}
