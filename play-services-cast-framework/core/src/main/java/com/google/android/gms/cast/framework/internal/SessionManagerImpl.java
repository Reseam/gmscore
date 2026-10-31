/*
 * Copyright (C) 2013-2017 microG Project Team
 * Copyright (C) 2026 Reseam contributors <https://reseam.app>
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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.framework.CastState;
import com.google.android.gms.cast.framework.ICastStateListener;
import com.google.android.gms.cast.framework.ISessionManager;
import com.google.android.gms.cast.framework.ISessionManagerListener;
import com.google.android.gms.cast.framework.ISessionProvider;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SessionManagerImpl extends ISessionManager.Stub {
    private static final String TAG = "GmsCastSessionManager";
    private final CastContextImpl castContext;
    private final RemoteCallbackList<ISessionManagerListener> sessionListeners = new RemoteCallbackList<>();
    private final RemoteCallbackList<ICastStateListener> stateListeners = new RemoteCallbackList<>();
    private final Map<String, Bundle> routes = new HashMap<>();
    private final SharedPreferences preferences;
    private volatile SessionImpl currentSession;
    private volatile int castState = CastState.NO_DEVICES_AVAILABLE;

    public SessionManagerImpl(CastContextImpl castContext) {
        this.castContext = castContext;
        preferences = castContext.getContext().getSharedPreferences("org.microg.gms.cast.sessions", 0);
    }

    @Override
    public IObjectWrapper getWrappedCurrentSession() throws RemoteException {
        SessionImpl session = currentSession;
        return session == null ? ObjectWrapper.wrap(null) : session.getWrappedSession();
    }

    SessionImpl getCurrentSession() {
        return currentSession;
    }

    @Override
    public void endCurrentSession(boolean selectDefaultRoute, boolean stopCasting) {
        castContext.runOnMain(() -> {
            clearSavedSession();
            if (currentSession != null) currentSession.end(stopCasting);
            if (selectDefaultRoute) selectDefaultRoute();
        });
    }

    @Override
    public void addSessionManagerListener(ISessionManagerListener listener) {
        if (listener != null) sessionListeners.register(listener);
    }

    @Override
    public void removeSessionManagerListener(ISessionManagerListener listener) {
        if (listener != null) sessionListeners.unregister(listener);
    }

    @Override
    public void addCastStateListener(ICastStateListener listener) {
        if (listener != null) stateListeners.register(listener);
    }

    @Override
    public void removeCastStateListener(ICastStateListener listener) {
        if (listener != null) stateListeners.unregister(listener);
    }

    @Override
    public IObjectWrapper getWrappedThis() {
        return ObjectWrapper.wrap(this);
    }

    @Override
    public int getCastState() {
        return castState;
    }

    @Override
    public void startSession(Bundle params) {
        if (params == null) return;
        String routeId = params.getString("CAST_INTENT_TO_CAST_ROUTE_ID_KEY");
        String sessionId = params.getString("CAST_INTENT_TO_CAST_SESSION_ID_KEY");
        castContext.runOnMain(() -> startRoute(routeId, null, sessionId));
    }

    public void onRouteSelected(String routeId, Bundle extras) {
        castContext.runOnMain(() -> startRoute(routeId, extras, null));
    }

    private void startRoute(String routeId, Bundle extras, String sessionId) {
        if (routeId == null) return;
        if (currentSession != null && routeId.equals(currentSession.getRouteId())) {
            if (currentSession.isSuspended()) resumeCurrentSession();
            return;
        }
        ISessionProvider provider = castContext.getDefaultSessionProvider();
        if (provider == null) return;
        SessionImpl session = null;
        try {
            Bundle routeExtras = extras != null ? extras : castContext.getRouter().getRouteInfoExtrasById(routeId);
            CastDevice device = CastDevice.getFromBundle(routeExtras);
            if (device == null) return;
            if (currentSession != null) currentSession.end(false);
            session = (SessionImpl) ObjectWrapper.unwrap(provider.getSession(sessionId));
            if (session == null) return;
            currentSession = session;
            session.start(castContext, device, routeId, routeExtras);
            if (currentSession == session && !session.isDisconnected()) castContext.getRouter().selectRouteById(routeId);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Error starting Cast session", e);
            if (session != null) session.onApplicationConnectionFailure(CastStatusCodes.ERROR_SERVICE_CREATION_FAILED);
        }
    }

    public void onRouteAdded(String routeId, Bundle extras) {
        castContext.runOnMain(() -> {
            if (CastDevice.getFromBundle(extras) == null) return;
            routes.put(routeId, new Bundle(extras));
            updateCastState();
            castContext.getDiscoveryManagerImpl().onDeviceAvailabilityChanged(true);
            if (currentSession != null) {
                if (routeId.equals(currentSession.getRouteId()) && currentSession.isSuspended()) resumeCurrentSession();
            } else if (castContext.getOptions().getResumeSavedSession()
                    && routeId.equals(preferences.getString("routeId", null))
                    && TextUtils.equals(castContext.getReceiverApplicationId(), preferences.getString("applicationId", null))) {
                startRoute(routeId, extras, preferences.getString("sessionId", null));
            }
        });
    }

    public void onRouteRemoved(String routeId) {
        castContext.runOnMain(() -> {
            routes.remove(routeId);
            updateCastState();
            castContext.getDiscoveryManagerImpl().onDeviceAvailabilityChanged(!routes.isEmpty());
        });
    }

    public void onRouteUnselected(String routeId) {
        castContext.runOnMain(() -> {
            if (currentSession != null && routeId.equals(currentSession.getRouteId())) {
                clearSavedSession();
                currentSession.end(false);
            }
        });
    }

    void resumeCurrentSession() {
        castContext.runOnMain(() -> {
            SessionImpl session = currentSession;
            if (session == null || !session.isSuspended()) return;
            try {
                session.resume();
            } catch (RemoteException e) {
                session.notifyFailedToResumeSession(CastStatusCodes.ERROR_SERVICE_DISCONNECTED);
            }
        });
    }

    private void updateCastState() {
        SessionImpl session = currentSession;
        int state = session == null ? (routes.isEmpty() ? CastState.NO_DEVICES_AVAILABLE : CastState.NOT_CONNECTED)
                : session.isConnected() ? CastState.CONNECTED
                : session.isConnecting() || session.isResuming() ? CastState.CONNECTING : CastState.NOT_CONNECTED;
        if (castState == state) return;
        castState = state;
        List<ICastStateListener> listeners = new ArrayList<>();
        int count = stateListeners.beginBroadcast();
        for (int i = 0; i < count; i++) listeners.add(stateListeners.getBroadcastItem(i));
        stateListeners.finishBroadcast();
        for (ICastStateListener listener : listeners) {
            try {
                listener.onCastStateChanged(state);
            } catch (RemoteException e) {
                Log.w(TAG, "Error notifying Cast state", e);
            }
        }
    }

    private void notifySessionListeners(SessionImpl session, SessionEvent event) {
        List<ISessionManagerListener> listeners = new ArrayList<>();
        int count = sessionListeners.beginBroadcast();
        for (int i = 0; i < count; i++) listeners.add(sessionListeners.getBroadcastItem(i));
        sessionListeners.finishBroadcast();
        try {
            IObjectWrapper wrapped = session.getWrappedSession();
            for (ISessionManagerListener listener : listeners) {
                try {
                    event.run(listener, wrapped);
                } catch (RemoteException e) {
                    Log.w(TAG, "Error notifying Cast session", e);
                }
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Error getting sender session", e);
        }
    }

    public void onSessionStarting(SessionImpl session) {
        updateCastState();
        notifySessionListeners(session, ISessionManagerListener::onSessionStarting);
    }

    public void onSessionStarted(SessionImpl session, String sessionId) {
        if (currentSession != session) return;
        saveSession(session);
        updateCastState();
        notifySessionListeners(session, (listener, wrapped) -> listener.onSessionStarted(wrapped, sessionId));
    }

    public void onSessionStartFailed(SessionImpl session, int error) {
        if (currentSession != session) return;
        currentSession = null;
        clearSavedSession();
        updateCastState();
        notifySessionListeners(session, (listener, wrapped) -> listener.onSessionStartFailed(wrapped, error));
        if (currentSession == null) selectDefaultRoute();
    }

    public void onSessionEnding(SessionImpl session) {
        notifySessionListeners(session, ISessionManagerListener::onSessionEnding);
    }

    public void onSessionEnded(SessionImpl session, int error) {
        if (currentSession != session) return;
        currentSession = null;
        clearSavedSession();
        updateCastState();
        notifySessionListeners(session, (listener, wrapped) -> listener.onSessionEnded(wrapped, error));
    }

    public void onSessionResuming(SessionImpl session, String sessionId) {
        updateCastState();
        notifySessionListeners(session, (listener, wrapped) -> listener.onSessionResuming(wrapped, sessionId));
    }

    public void onSessionResumed(SessionImpl session, boolean wasSuspended) {
        if (currentSession != session) return;
        saveSession(session);
        updateCastState();
        notifySessionListeners(session, (listener, wrapped) -> listener.onSessionResumed(wrapped, wasSuspended));
    }

    public void onSessionResumeFailed(SessionImpl session, int error) {
        if (currentSession != session) return;
        currentSession = null;
        clearSavedSession();
        updateCastState();
        notifySessionListeners(session, (listener, wrapped) -> listener.onSessionResumeFailed(wrapped, error));
        if (currentSession == null) selectDefaultRoute();
    }

    public void onSessionSuspended(SessionImpl session, int reason) {
        if (currentSession != session) return;
        updateCastState();
        notifySessionListeners(session, (listener, wrapped) -> listener.onSessionSuspended(wrapped, reason));
    }

    private void saveSession(SessionImpl session) {
        if (!castContext.getOptions().getResumeSavedSession()) return;
        preferences.edit().putString("applicationId", castContext.getReceiverApplicationId())
                .putString("routeId", session.getRouteId()).putString("sessionId", session.getSessionId()).apply();
    }

    private void clearSavedSession() {
        preferences.edit().clear().apply();
    }

    private void selectDefaultRoute() {
        try {
            castContext.getRouter().selectDefaultRoute();
        } catch (RemoteException e) {
            Log.w(TAG, "Error selecting default route", e);
        }
    }

    void destroy() {
        SessionImpl session = currentSession;
        if (session != null) {
            session.end(false);
            session.notifySessionEnded(CastStatusCodes.SUCCESS);
        }
        routes.clear();
        sessionListeners.kill();
        stateListeners.kill();
    }

    private interface SessionEvent {
        void run(ISessionManagerListener listener, IObjectWrapper session) throws RemoteException;
    }
}
