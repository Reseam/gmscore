/*
 * Copyright (C) 2013-2017 microG Project Team
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
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.framework.ISession;
import com.google.android.gms.cast.framework.ISessionProxy;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

public class SessionImpl extends ISession.Stub {
    private static final String TAG = "GmsCastSession";
    private enum State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, RESUMING, SUSPENDED }

    private final String category;
    private final ISessionProxy proxy;
    private volatile State state = State.DISCONNECTED;
    private volatile String sessionId;
    private volatile String routeId;
    private CastSessionImpl castSession;
    private CastContextImpl castContext;
    private Bundle routeInfoExtra;
    private boolean resumingFromSuspended;

    public SessionImpl(String category, String sessionId, ISessionProxy proxy) {
        this.category = category;
        this.sessionId = sessionId;
        this.proxy = proxy;
    }

    public void start(CastContextImpl context, CastDevice device, String routeId, Bundle extras) throws RemoteException {
        castContext = context;
        this.routeId = routeId;
        routeInfoExtra = new Bundle(extras);
        device.putInBundle(routeInfoExtra);
        if (sessionId != null) {
            resume();
        } else {
            state = State.CONNECTING;
            proxy.onStarting(routeInfoExtra);
            if (!isConnecting()) return;
            context.getSessionManagerImpl().onSessionStarting(this);
            if (isConnecting()) proxy.start(routeInfoExtra);
        }
    }

    public void resume() throws RemoteException {
        if (castContext == null || routeInfoExtra == null || isResuming()) return;
        resumingFromSuspended = isSuspended();
        state = State.RESUMING;
        proxy.onResuming(routeInfoExtra);
        if (!isResuming()) return;
        castContext.getSessionManagerImpl().onSessionResuming(this, sessionId);
        if (isResuming()) proxy.resume(routeInfoExtra);
    }

    public void end(boolean stopCasting) {
        if (isDisconnected() || isDisconnecting()) return;
        state = State.DISCONNECTING;
        castContext.getSessionManagerImpl().onSessionEnding(this);
        try {
            proxy.end(stopCasting);
        } catch (RemoteException e) {
            Log.w(TAG, "Error ending Cast session", e);
            notifySessionEnded(CastStatusCodes.ERROR_SERVICE_DISCONNECTED);
        }
    }

    public void onApplicationConnectionSuccess(ApplicationMetadata metadata, String status, String sessionId, boolean launched) {
        runOnMain(() -> {
            if (isResuming()) {
                this.sessionId = sessionId;
                notifySessionResumed(resumingFromSuspended);
            } else {
                notifySessionStarted(sessionId);
            }
        });
    }

    public void onApplicationConnectionFailure(int statusCode) {
        runOnMain(() -> {
            if (isResuming()) notifyFailedToResumeSession(statusCode);
            else notifyFailedToStartSession(statusCode);
        });
    }

    public CastSessionImpl getCastSession() {
        return castSession;
    }

    public void setCastSession(CastSessionImpl castSession) {
        this.castSession = castSession;
    }

    public ISessionProxy getSessionProxy() {
        return proxy;
    }

    public IObjectWrapper getWrappedSession() throws RemoteException {
        return proxy.getWrappedSession();
    }

    @Override
    public String getCategory() {
        return category;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public String getRouteId() {
        return routeId;
    }

    @Override
    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    @Override
    public boolean isConnecting() {
        return state == State.CONNECTING;
    }

    @Override
    public boolean isDisconnecting() {
        return state == State.DISCONNECTING;
    }

    @Override
    public boolean isDisconnected() {
        return state == State.DISCONNECTED;
    }

    @Override
    public boolean isResuming() {
        return state == State.RESUMING;
    }

    @Override
    public boolean isSuspended() {
        return state == State.SUSPENDED;
    }

    @Override
    public void notifySessionStarted(String sessionId) {
        runOnMain(() -> {
            if (!isConnecting()) return;
            this.sessionId = sessionId;
            state = State.CONNECTED;
            castContext.getSessionManagerImpl().onSessionStarted(this, sessionId);
        });
    }

    @Override
    public void notifyFailedToStartSession(int error) {
        runOnMain(() -> {
            if (!isConnecting()) return;
            finish(error, false, true);
        });
    }

    @Override
    public void notifySessionEnded(int error) {
        runOnMain(() -> {
            if (!isDisconnected()) finish(error, false, false);
        });
    }

    @Override
    public void notifySessionResumed(boolean wasSuspended) {
        runOnMain(() -> {
            if (!isResuming() && !isSuspended()) return;
            state = State.CONNECTED;
            castContext.getSessionManagerImpl().onSessionResumed(this, wasSuspended);
        });
    }

    @Override
    public void notifyFailedToResumeSession(int error) {
        runOnMain(() -> {
            if (isResuming()) finish(error, true, false);
        });
    }

    @Override
    public void notifySessionSuspended(int reason) {
        runOnMain(() -> {
            if (!isConnected()) return;
            state = State.SUSPENDED;
            castContext.getSessionManagerImpl().onSessionSuspended(this, reason);
        });
    }

    private void finish(int error, boolean resumeFailed, boolean startFailed) {
        state = State.DISCONNECTED;
        if (castSession != null) castSession.closeConnection(error);
        SessionManagerImpl manager = castContext.getSessionManagerImpl();
        if (resumeFailed) manager.onSessionResumeFailed(this, error);
        else if (startFailed) manager.onSessionStartFailed(this, error);
        else manager.onSessionEnded(this, error);
        sessionId = null;
        routeId = null;
        routeInfoExtra = null;
        castContext = null;
    }

    private void runOnMain(Runnable action) {
        CastContextImpl context = castContext;
        if (context != null) context.runOnMain(() -> {
            if (castContext == context) action.run();
        });
    }

    @Override
    public int getSupportedVersion() {
        return 1;
    }

    @Override
    public IObjectWrapper getWrappedObject() {
        return ObjectWrapper.wrap(this);
    }
}
