package com.tlean07.totvs.util;

import com.tlean07.totvs.network.NetworkResult;

/** Result delivered on the main thread. */
public interface Callback<T> {
    void onResult(NetworkResult<T> result);
}
