package com.uriio.beacons.api;

import com.google.gson.JsonParseException;
import com.uriio.beacons.Beacons;

import java.io.IOException;
import java.lang.annotation.Annotation;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Handles a valid API or server error response and calls a higher-level result callback.
 * Created on 5/5/2016.
 */
public class SimpleResultHandler<T> implements Callback<T> {
    private final Beacons.OnResultListener<T> callback;

    public SimpleResultHandler(Beacons.OnResultListener<T> callback) {
        this.callback = callback;
    }

    @Override
    public void onResponse(Call<T> call, Response<T> response) {
        if (response.isSuccessful()) {
            callback.onResult(response.body(), null);
        } else {
            callback.onResult(null, new Exception(extractError(response)));
        }
    }

    @Override
    public void onFailure(Call<T> call, Throwable t) {
        callback.onResult(null, t);
    }

    private static String extractError(Response response) {
        String error = "Unknown error";
        if (response.errorBody() != null) {
            try {
                ErrorHolder errorModel = (ErrorHolder) ApiClient.getRetrofit()
                        .responseBodyConverter(ErrorHolder.class, new Annotation[0])
                        .convert(response.errorBody());
                error = errorModel.getError().message;
            } catch (IOException | JsonParseException ignored) {
                // http error 5xx or non-json content
            }
        }
        return error;
    }
}
