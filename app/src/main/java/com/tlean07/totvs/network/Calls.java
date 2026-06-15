package com.tlean07.totvs.network;

import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.Callback;

import retrofit2.Call;
import retrofit2.Response;

/** Executes a Retrofit Call off the main thread and delivers a NetworkResult on it. */
public final class Calls {

    private Calls() {
    }

    public static <T> void execute(Call<T> call, Callback<T> callback) {
        AppExecutors.io(() -> {
            try {
                Response<T> response = call.execute();
                if (response.isSuccessful()) {
                    T body = response.body();
                    AppExecutors.main(() -> callback.onResult(NetworkResult.ok(body)));
                } else {
                    String message = ErrorMapper.fromResponse(response);
                    AppExecutors.main(() -> callback.onResult(NetworkResult.error(message)));
                }
            } catch (Exception e) {
                String message = ErrorMapper.fromThrowable(e);
                AppExecutors.main(() -> callback.onResult(NetworkResult.error(message)));
            }
        });
    }
}
