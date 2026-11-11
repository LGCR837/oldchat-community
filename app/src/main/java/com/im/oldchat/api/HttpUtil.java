package com.im.oldchat.api;

import android.os.AsyncTask;
import android.util.Log;

import com.im.oldchat.util.AsyncTaskExecutionCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;

public class HttpUtil extends HttpUtilSupport1 {
    private static final String TAG = "HttpUtil";

    public interface Callback {
        void onSuccess(String response);
        void onError(int code, String error);
    }

    public interface StreamProvider {
        InputStream open() throws Exception;
        long length();
    }

    public interface ProgressCallback {
        void onProgress(long written, long total);
    }

    /** 显式登录/退出前废弃所有旧账号的后台刷新结果。 */
    public static void invalidateAuthOperations() {
        HttpAuthHelper.invalidateAuthOperations();
    }

    private static <P, Pr, R> void executeTask(AsyncTask<P, Pr, R> task, P... params) {
        AsyncTaskExecutionCompat.execute(task, params);
    }

    private static void dispatchResult(Result result, Callback callback) {
        if (callback == null) {
            return;
        }
        int code = result == null ? -1 : result.code;
        String data = result == null ? "empty response" : result.data;
        try {
            if (code >= 200 && code < 300) {
                callback.onSuccess(data);
            } else {
                callback.onError(code, data);
            }
        } catch (Throwable t) {
            Log.e(TAG, "callback crashed", t);
        }
    }

    public static void post(final String path, final JSONObject json, final String token,
                            final Callback callback) {
        executeTask(new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                return requestWithRefresh("POST", path, json, token);
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }

    public static void get(final String path, final String token, final Callback callback) {
        executeTask(new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                return requestWithRefresh("GET", path, null, token);
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }

    public static void delete(final String path, final String token, final Callback callback) {
        executeTask(new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                return requestWithRefresh("DELETE", path, null, token);
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }

    public static void postMultipart(final String path, final byte[] data, final String fileName,
                                     final String contentType, final String token,
                                     final Callback callback) {
        executeTask(new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                try {
                    Result result = executeMultipart(path, data, fileName, contentType,
                            null, null, null, token);
                    if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED
                            && shouldAttemptRefresh(path, token)) {
                        String newToken = HttpAuthHelper.refreshAccessToken();
                        if (newToken != null) {
                            result = executeMultipart(path, data, fileName, contentType,
                                    null, null, null, newToken);
                            return applyAuthPolicy(result, path, newToken);
                        }
                    }
                    return applyAuthPolicy(result, path, token);
                } catch (Exception e) {
                    return new Result(-1, e.getMessage());
                }
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }

    public static void postMultipartNamed(final String path, final String fieldName, final byte[] data,
                                          final String fileName, final String contentType,
                                          final String token, final Callback callback) {
        executeTask(new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                try {
                    Result result = executeMultipartNamed(path, fieldName, data, fileName, contentType,
                            null, null, null, token);
                    if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED
                            && shouldAttemptRefresh(path, token)) {
                        String newToken = HttpAuthHelper.refreshAccessToken();
                        if (newToken != null) {
                            result = executeMultipartNamed(path, fieldName, data, fileName, contentType,
                                    null, null, null, newToken);
                            return applyAuthPolicy(result, path, newToken);
                        }
                    }
                    return applyAuthPolicy(result, path, token);
                } catch (Exception e) {
                    return new Result(-1, e.getMessage());
                }
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }

    public static void postMultipartWithThumb(final String path, final byte[] data,
                                              final String fileName, final String contentType,
                                              final byte[] thumbData, final String thumbName,
                                              final String thumbType, final String token,
                                              final Callback callback) {
        executeTask(new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                try {
                    Result result = executeMultipart(path, data, fileName, contentType,
                            thumbData, thumbName, thumbType, token);
                    if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED
                            && shouldAttemptRefresh(path, token)) {
                        String newToken = HttpAuthHelper.refreshAccessToken();
                        if (newToken != null) {
                            result = executeMultipart(path, data, fileName, contentType,
                                    thumbData, thumbName, thumbType, newToken);
                            return applyAuthPolicy(result, path, newToken);
                        }
                    }
                    return applyAuthPolicy(result, path, token);
                } catch (Exception e) {
                    return new Result(-1, e.getMessage());
                }
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }

    public static void postMultipartWithThumb(final String path, final byte[] data,
                                              final String fileName, final String contentType,
                                              final byte[] thumbData, final String thumbName,
                                              final String thumbType, final String token,
                                              final ProgressCallback progress,
                                              final Callback callback) {
        executeTask(new AsyncTask<Void, Integer, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                try {
                    Result result = executeMultipartWithProgress(path, data, fileName, contentType,
                            thumbData, thumbName, thumbType, token, new ProgressCallback() {
                                @Override
                                public void onProgress(long written, long total) {
                                    if (progress != null) {
                                        int percent = (int) ((written * 100) / total);
                                        publishProgress(percent);
                                    }
                                }
                            });
                    if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED
                            && shouldAttemptRefresh(path, token)) {
                        String newToken = HttpAuthHelper.refreshAccessToken();
                        if (newToken != null) {
                            result = executeMultipartWithProgress(path, data, fileName, contentType,
                                    thumbData, thumbName, thumbType, newToken,
                                    new ProgressCallback() {
                                        @Override
                                        public void onProgress(long written, long total) {
                                            if (progress != null) {
                                                int percent = (int) ((written * 100) / total);
                                                publishProgress(percent);
                                            }
                                        }
                                    });
                            return applyAuthPolicy(result, path, newToken);
                        }
                    }
                    return applyAuthPolicy(result, path, token);
                } catch (Exception e) {
                    return new Result(-1, e.getMessage());
                }
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                if (progress != null && values.length > 0) {
                    progress.onProgress(values[0], 100);
                }
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }

    public static void postMultipartStream(final String path, final StreamProvider provider,
                                           final String fileName, final String contentType,
                                           final String token, final Callback callback) {
        executeTask(new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                return requestMultipartStream(path, provider, fileName, contentType, token,
                        null, null, null);
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }

    public static void postMultipartStream(final String path, final StreamProvider provider,
                                           final String fileName, final String contentType,
                                           final String token, final ProgressCallback progress,
                                           final Callback callback) {
        executeTask(new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                return requestMultipartStream(path, provider, fileName, contentType, token,
                        null, null, progress);
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }

    public static void postMultipartStream(final String path, final StreamProvider provider,
                                           final String fileName, final String contentType,
                                           final String token, final String fieldName,
                                           final String fieldValue, final ProgressCallback progress,
                                           final Callback callback) {
        executeTask(new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                return requestMultipartStream(path, provider, fileName, contentType, token,
                        fieldName, fieldValue, progress);
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }

    public static void postMultipartStreamWithThumb(final String path, final StreamProvider provider,
                                                    final String fileName, final String contentType,
                                                    final byte[] thumbData, final String thumbName,
                                                    final String thumbType, final String token,
                                                    final ProgressCallback progress,
                                                    final Callback callback) {
        postMultipartStreamWithLyrics(path, provider, fileName, contentType,
                thumbData, thumbName, thumbType, null, null, null,
                token, progress, callback);
    }

    public static void postMultipartStreamWithLyrics(final String path, final StreamProvider provider,
                                                     final String fileName, final String contentType,
                                                     final byte[] thumbData, final String thumbName,
                                                     final String thumbType,
                                                     final byte[] lyricsData, final String lyricsName,
                                                     final String lyricsType,
                                                     final String token,
                                                     final ProgressCallback progress,
                                                     final Callback callback) {
        executeTask(new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                return requestMultipartStreamWithLyrics(path, provider, fileName, contentType,
                        thumbData, thumbName, thumbType, lyricsData, lyricsName, lyricsType,
                        token, progress);
            }

            @Override
            protected void onPostExecute(Result result) {
                dispatchResult(result, callback);
            }
        });
    }
}
