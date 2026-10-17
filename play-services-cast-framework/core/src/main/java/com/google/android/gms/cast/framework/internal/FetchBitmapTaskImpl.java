/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.cast.framework.internal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import com.google.android.gms.cast.framework.media.internal.IFetchBitmapTask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class FetchBitmapTaskImpl extends IFetchBitmapTask.Stub {
    private static final String TAG = "GmsCastBitmap";
    private final int width;
    private final int height;
    private final long maxBytes;
    private final int timeout;

    public FetchBitmapTaskImpl(int width, int height, long maxBytes, int timeout) {
        this.width = width > 0 ? Math.min(width, 2048) : 512;
        this.height = height > 0 ? Math.min(height, 2048) : 512;
        this.maxBytes = maxBytes > 0 ? Math.min(maxBytes, 8 * 1024 * 1024) : 2 * 1024 * 1024;
        this.timeout = timeout > 0 ? Math.min(timeout, 30000) : 10000;
    }

    @Override
    public Bitmap fetchBitmap(Uri uri) {
        if (uri == null || (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            if (connection.getContentLength() > maxBytes) return null;
            byte[] bytes;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if ((long) output.size() + count > maxBytes) return null;
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > width || options.outHeight / options.inSampleSize > height) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Error fetching Cast artwork", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
