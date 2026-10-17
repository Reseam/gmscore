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

import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.cast.framework.IDiscoveryManager;
import com.google.android.gms.cast.framework.IDiscoveryManagerListener;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

import java.util.ArrayList;
import java.util.List;

public class DiscoveryManagerImpl extends IDiscoveryManager.Stub {
    private static final String TAG = "GmsCastDiscovery";
    private final CastContextImpl context;
    private final RemoteCallbackList<IDiscoveryManagerListener> listeners = new RemoteCallbackList<>();
    private boolean available;

    public DiscoveryManagerImpl(CastContextImpl context) {
        this.context = context;
    }

    @Override
    public void startDiscovery() {
        context.requestDiscovery(true);
    }

    @Override
    public void stopDiscovery() {
        context.requestDiscovery(false);
    }

    @Override
    public void addDiscoveryManagerListener(IDiscoveryManagerListener listener) {
        if (listener != null) listeners.register(listener);
    }

    @Override
    public void removeDiscoveryManagerListener(IDiscoveryManagerListener listener) {
        if (listener != null) listeners.unregister(listener);
    }

    void onDeviceAvailabilityChanged(boolean available) {
        if (this.available == available) return;
        this.available = available;
        List<IDiscoveryManagerListener> callbacks = new ArrayList<>();
        int count = listeners.beginBroadcast();
        for (int i = 0; i < count; i++) callbacks.add(listeners.getBroadcastItem(i));
        listeners.finishBroadcast();
        for (IDiscoveryManagerListener listener : callbacks) {
            try {
                listener.onDeviceAvailabilityChanged(available);
            } catch (RemoteException e) {
                Log.w(TAG, "Error notifying device availability", e);
            }
        }
    }

    void destroy() {
        listeners.kill();
    }

    @Override
    public IObjectWrapper getWrappedThis() {
        return ObjectWrapper.wrap(this);
    }
}
