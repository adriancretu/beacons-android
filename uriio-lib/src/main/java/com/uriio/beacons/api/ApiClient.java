package com.uriio.beacons.api;

import com.uriio.beacons.Beacons;

import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Created on 4/27/2016.
 */
public class ApiClient {
    private interface UriioService {
        @POST("urls")
        Call<Url> registerUrl(@Body Url url);

        @PUT("urls/{id}")
        Call<Url> updateUrl(@Path("id") long id, @Body Url url);

        @POST("urls/{id}")
        Call<ShortUrls> createShortUrl(@Path("id") long id, @Body IssueUrls issueUrls);

        @GET("urls/{id}")
        Call<Url> getUrl(@Path("id") long id, @Query("apiKey") String apiKey,
                         @Query("token") String token);

        @DELETE("urls/{id}")
        Call<Url> deleteUrl(@Path("id") long id, @Query("apiKey") String apiKey,
                            @Query("token") String token);
    }

    private static final String ROOT_SERVICE_URL = "https://api.uriio.com/v1/";

    private static Retrofit _instance;
    private final String mApiKey;
    private final UriioService mApiService;

    public static Retrofit getRetrofit() {
        if (null == _instance) {
            _instance = new Retrofit.Builder()
                    .baseUrl(ROOT_SERVICE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return _instance;
    }

    public ApiClient(String apiKey) {
        mApiKey = apiKey;
        mApiService = getRetrofit().create(ApiClient.UriioService.class);
    }

    /**
     * Registers a new long URL resource.
     * @param url           The long URL.
     * @param urlPublicKey  The public key of the new URL. Each URL should have its own key-pair.
     *                      If null, a public key will be generated using Curve25519.generateKeyPair()
     * @param callback      Result callback.
     */
    public void registerUrl(String url, byte[] urlPublicKey, Beacons.OnResultListener<Url> callback) {
        if (null == urlPublicKey) {
            Curve25519KeyPair keyPair = Curve25519.getInstance(Curve25519.BEST).generateKeyPair();
            urlPublicKey = keyPair.getPublicKey();
        }

        mApiService.registerUrl(new Url(mApiKey, url, urlPublicKey))
                .enqueue(new SimpleResultHandler<>(callback));
    }

    /**
     * Requests a new short URL for the specified resource.
     * @param urlId       The registered URL's ID.
     * @param urlToken    The URL token.
     * @param ttl         Time To Live for the returned short URL (or 0 to never expire).
     * @param numToIssue  How many short URLs to request.
     * @param callback    Result callback.
     */
    public void issueShortUrls(long urlId, String urlToken, int ttl, int numToIssue,
                               Beacons.OnResultListener<ShortUrls> callback) {
        mApiService.createShortUrl(urlId, new IssueUrls(mApiKey, urlToken, ttl, numToIssue))
                .enqueue(new SimpleResultHandler<>(callback));
    }

    public void updateUrl(long urlId, String urlToken, String longUrl, Beacons.OnResultListener<Url> callback) {
        mApiService.updateUrl(urlId, new Url(mApiKey, urlToken, longUrl))
                .enqueue(new SimpleResultHandler<>(callback));
    }
}