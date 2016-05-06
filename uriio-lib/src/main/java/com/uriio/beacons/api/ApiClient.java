package com.uriio.beacons.api;

import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Created on 4/27/2016.
 */
public class ApiClient {
    public interface UriioService {
        @POST("urls")
        Call<Url> registerUrl(@Body Url url, @Query("apiKey") String apiKey);

        @POST("urls/{id}")
        Call<ShortUrls> createShortUrl(@Path("id") long id, @Query("apiKey") String apiKey,
                                      @Body IssueUrls issueUrls);

        @GET("urls/{id}")
        Call<Url> getUrl(@Path("id") long id, @Query("apiKey") String apiKey,
                         @Query("token") String token);

        @DELETE("urls/{id}")
        Call<Url> deleteUrl(@Path("id") long id, @Query("apiKey") String apiKey,
                            @Query("token") String token);
    }

    private static final String ROOT_SERVICE_URL = "https://api.uriio.com/v1/";

    private static Retrofit _instance;

    public static Retrofit getRetrofit() {
        if (null == _instance) {
            _instance = new Retrofit.Builder()
                    .baseUrl(ROOT_SERVICE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return _instance;
    }
}