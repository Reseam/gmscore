/*
 * SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.cast.framework.internal;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.framework.IReconnectionService;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

public class ReconnectionServiceImpl extends IReconnectionService.Stub {
    private final Service service;
    private final SessionManagerImpl sessionManager;
    private final DiscoveryManagerImpl discoveryManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reconnect = this::reconnect;
    private long deadline;
    private int startId;

    public ReconnectionServiceImpl(IObjectWrapper service, IObjectWrapper sessionManager, IObjectWrapper discoveryManager) {
        this.service = (Service) ObjectWrapper.unwrap(service);
        this.sessionManager = (SessionManagerImpl) ObjectWrapper.unwrap(sessionManager);
        this.discoveryManager = (DiscoveryManagerImpl) ObjectWrapper.unwrap(discoveryManager);
    }

    @Override
    public void onCreate() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.post(() -> {
            this.startId = startId;
            deadline = SystemClock.elapsedRealtime() + 30000;
            handler.removeCallbacks(reconnect);
            discoveryManager.startDiscovery();
            reconnect();
        });
        return Service.START_NOT_STICKY;
    }

    private void reconnect() {
        SessionImpl session = sessionManager.getCurrentSession();
        if (session == null || session.isConnected() || session.isDisconnected()) {
            discoveryManager.stopDiscovery();
            service.stopSelf(startId);
            return;
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            session.notifySessionEnded(CastStatusCodes.TIMEOUT);
            discoveryManager.stopDiscovery();
            service.stopSelf(startId);
            return;
        }
        sessionManager.resumeCurrentSession();
        handler.postDelayed(reconnect, 1000);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        discoveryManager.stopDiscovery();
    }
}
