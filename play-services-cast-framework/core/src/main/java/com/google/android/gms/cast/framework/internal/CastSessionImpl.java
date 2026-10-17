/*
 * Copyright (C) 2013-2017 microG Project Team
 * Copyright (C) 2026 Cossale <hello@auna.li>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gms.cast.framework.internal;

import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.ICastConnectionController;
import com.google.android.gms.cast.framework.ICastSession;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

public class CastSessionImpl extends ICastSession.Stub {
    private static final String TAG = "GmsCastSession";
    private final CastOptions options;
    private final SessionImpl session;
    private final ICastConnectionController controller;
    private boolean stopReceiver;

    public CastSessionImpl(CastOptions options, IObjectWrapper session, ICastConnectionController controller) {
        this.options = options;
        this.session = (SessionImpl) ObjectWrapper.unwrap(session);
        this.controller = controller;
        stopReceiver = options.getStopReceiverApplicationWhenEndingSession();
        this.session.setCastSession(this);
    }

    @Override
    public void onConnected(Bundle extras) throws RemoteException {
        if (session.isResuming() && session.getSessionId() != null) {
            controller.joinApplication(options.getReceiverApplicationId(), session.getSessionId());
        } else if (session.isConnecting()) {
            controller.launchApplication(options.getReceiverApplicationId(), options.getLaunchOptions());
        }
    }

    @Override
    public void onConnectionSuspended(int reason) {
        session.notifySessionSuspended(reason);
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        session.onApplicationConnectionFailure(result == null ? CastStatusCodes.INTERNAL_ERROR : result.getErrorCode());
    }

    @Override
    public void onApplicationConnectionSuccess(ApplicationMetadata metadata, String status, String sessionId, boolean launched) {
        session.onApplicationConnectionSuccess(metadata, status, sessionId, launched);
    }

    @Override
    public void onApplicationConnectionFailure(int statusCode) {
        session.onApplicationConnectionFailure(statusCode);
    }

    @Override
    public void disconnectFromDevice(boolean stopCasting, int reason) {
        if (session.isDisconnected()) return;
        try {
            if ((stopCasting || stopReceiver) && session.getSessionId() != null) {
                controller.stopApplication(session.getSessionId());
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Error stopping receiver application", e);
        } finally {
            session.notifySessionEnded(reason);
        }
    }

    void closeConnection(int reason) {
        try {
            controller.closeConnection(reason);
        } catch (RemoteException e) {
            Log.w(TAG, "Error closing Cast connection", e);
        }
    }

    @Override
    public void setStopReceiverApplicationWhenEndingSession(boolean stopReceiver) {
        this.stopReceiver = stopReceiver;
    }
}
