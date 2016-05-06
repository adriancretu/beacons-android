package com.uriio.beacons.api;

import com.google.gson.annotations.SerializedName;

/**
 * Created on 5/4/2016.
 */
public class ErrorHolder {
    @SerializedName("error")
    private Error error;

    public Error getError() {
        return error;
    }
}