/*
 * SPDX-FileCopyrightText: 2026 Reseam contributors <https://reseam.app>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast;

import android.os.Bundle;
import android.os.RemoteException;

import com.google.android.gms.cast.RequestItem;
import com.google.android.gms.cast.internal.IBundleCallback;
import com.google.android.gms.cast.internal.ICastService;
import com.google.android.gms.common.api.ApiMetadata;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.common.api.internal.IStatusCallback;

import java.util.List;

public class CastServiceImpl extends ICastService.Stub {
    @Override
    public void broadcastPrecacheMessageLegacy(IStatusCallback callback, String[] applicationIds, String precacheData) throws RemoteException {
        if (callback != null) callback.onResult(new Status(CommonStatusCodes.ERROR));
    }

    @Override
    public void broadcastPrecacheMessage(IStatusCallback callback, String[] applicationIds, String precacheData, List<RequestItem> items) throws RemoteException {
        broadcastPrecacheMessageLegacy(callback, applicationIds, precacheData);
    }

    @Override
    public void getCxLessStatus(IStatusCallback callback) throws RemoteException {
        if (callback != null) callback.onResult(Status.SUCCESS);
    }

    @Override
    public void getCastStatusCodeDictionary(IBundleCallback callback, String[] names, ApiMetadata metadata) throws RemoteException {
        returnDefaults(callback, metadata);
    }

    @Override
    public void getFeatureFlags(IBundleCallback callback, String[] names, ApiMetadata metadata) throws RemoteException {
        returnDefaults(callback, metadata);
    }

    @Override
    public void getIntegerMappings(IBundleCallback callback, String[] names, ApiMetadata metadata) throws RemoteException {
        returnDefaults(callback, metadata);
    }

    private void returnDefaults(IBundleCallback callback, ApiMetadata metadata) throws RemoteException {
        if (callback != null) {
            callback.onBundle(new Bundle(), metadata == null || metadata.skip ? ApiMetadata.SKIP : ApiMetadata.DEFAULT);
        }
    }
}
