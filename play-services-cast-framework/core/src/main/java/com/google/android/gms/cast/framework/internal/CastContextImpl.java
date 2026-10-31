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

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.IAppVisibilityListener;
import com.google.android.gms.cast.framework.ICastContext;
import com.google.android.gms.cast.framework.ISessionProvider;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CastContextImpl extends ICastContext.Stub implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "GmsCastContext";
    private final Context context;
    private final CastOptions options;
    private final IMediaRouter router;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ISessionProvider> sessionProviders = new HashMap<>();
    private final Set<Activity> startedActivities = new HashSet<>();
    private final RemoteCallbackList<IAppVisibilityListener> visibilityListeners = new RemoteCallbackList<>();
    private final SessionManagerImpl sessionManager;
    private final DiscoveryManagerImpl discoveryManager;
    private final MediaRouterCallbackImpl callback;
    private volatile MediaRouteSelector mergedSelector;
    private String receiverApplicationId;
    private volatile boolean visible = true;
    private boolean discoveryRequested;
    private boolean destroyed;

    public CastContextImpl(IObjectWrapper context, CastOptions options, IMediaRouter router, Map<String, IBinder> providers) {
        Context unwrapped = (Context) ObjectWrapper.unwrap(context);
        this.context = unwrapped.getApplicationContext();
        this.options = options;
        this.router = router;
        receiverApplicationId = options.getReceiverApplicationId();
        setProviders(providers);
        sessionManager = new SessionManagerImpl(this);
        discoveryManager = new DiscoveryManagerImpl(this);
        callback = new MediaRouterCallbackImpl(this);
        runOnMain(() -> {
            if (this.context instanceof Application) {
                ((Application) this.context).registerActivityLifecycleCallbacks(this);
            }
            registerRouter();
        });
    }

    private void setProviders(Map<String, IBinder> providers) {
        sessionProviders.clear();
        MediaRouteSelector.Builder builder = new MediaRouteSelector.Builder();
        for (Map.Entry<String, IBinder> entry : providers.entrySet()) {
            ISessionProvider provider = ISessionProvider.Stub.asInterface(entry.getValue());
            if (provider != null) {
                sessionProviders.put(entry.getKey(), provider);
                builder.addControlCategory(entry.getKey());
            }
        }
        mergedSelector = builder.build();
    }

    ISessionProvider getDefaultSessionProvider() {
        String category = CastMediaControlIntent.categoryForCast(receiverApplicationId);
        for (Map.Entry<String, ISessionProvider> entry : sessionProviders.entrySet()) {
            if (entry.getKey().equals(category) || entry.getKey().startsWith(category + "/")) return entry.getValue();
        }
        return sessionProviders.size() == 1 ? sessionProviders.values().iterator().next() : null;
    }

    private void registerRouter() {
        try {
            router.registerMediaRouterCallbackImpl(mergedSelector.asBundle(), callback);
            updateDiscovery();
        } catch (RemoteException e) {
            Log.w(TAG, "Error registering Cast router", e);
        }
    }

    void requestDiscovery(boolean requested) {
        runOnMain(() -> {
            discoveryRequested = requested;
            updateDiscovery();
        });
    }

    private void updateDiscovery() {
        try {
            router.removeCallback(mergedSelector.asBundle());
            if (!destroyed) {
                int flags = MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY;
                if (visible || discoveryRequested) flags |= MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN;
                router.addCallback(mergedSelector.asBundle(), flags);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Error updating Cast discovery", e);
        }
    }

    @Override
    public Bundle getMergedSelectorAsBundle() {
        return mergedSelector.asBundle();
    }

    @Override
    public void addVisibilityChangeListener(IAppVisibilityListener listener) {
        if (listener != null) visibilityListeners.register(listener);
    }

    @Override
    public void removeVisibilityChangeListener(IAppVisibilityListener listener) {
        if (listener != null) visibilityListeners.unregister(listener);
    }

    @Override
    public boolean isApplicationVisible() {
        return visible;
    }

    private void setVisible(boolean visible) {
        if (this.visible == visible || destroyed) return;
        this.visible = visible;
        updateDiscovery();
        List<IAppVisibilityListener> listeners = new ArrayList<>();
        int count = visibilityListeners.beginBroadcast();
        for (int i = 0; i < count; i++) listeners.add(visibilityListeners.getBroadcastItem(i));
        visibilityListeners.finishBroadcast();
        for (IAppVisibilityListener listener : listeners) {
            try {
                if (visible) listener.onAppEnteredForeground();
                else listener.onAppEnteredBackground();
            } catch (RemoteException e) {
                Log.w(TAG, "Error notifying application visibility", e);
            }
        }
        if (visible) sessionManager.resumeCurrentSession();
    }

    @Override
    public SessionManagerImpl getSessionManagerImpl() {
        return sessionManager;
    }

    @Override
    public DiscoveryManagerImpl getDiscoveryManagerImpl() {
        return discoveryManager;
    }

    @Override
    public void destroy() {
        runOnMain(() -> {
            if (destroyed) return;
            sessionManager.destroy();
            destroyed = true;
            updateDiscovery();
            try {
                router.clearCallbacks();
            } catch (RemoteException e) {
                Log.w(TAG, "Error clearing Cast callbacks", e);
            }
            if (context instanceof Application) ((Application) context).unregisterActivityLifecycleCallbacks(this);
            startedActivities.clear();
            discoveryManager.destroy();
            visibilityListeners.kill();
        });
    }

    @Override
    public void onActivityResumed(IObjectWrapper activity) {
        Activity resumed = ObjectWrapper.unwrapTyped(activity, Activity.class);
        runOnMain(() -> {
            if (resumed != null) startedActivities.add(resumed);
            setVisible(true);
        });
    }

    @Override
    public void onActivityPaused(IObjectWrapper activity) {
    }

    @Override
    public void setReceiverApplicationId(String applicationId, Map providers) {
        runOnMain(() -> {
            try {
                router.removeCallback(mergedSelector.asBundle());
                router.clearCallbacks();
            } catch (RemoteException e) {
                Log.w(TAG, "Error replacing Cast selector", e);
            }
            receiverApplicationId = applicationId;
            setProviders(providers);
            registerRouter();
        });
    }

    public Context getContext() {
        return context;
    }

    public IMediaRouter getRouter() {
        return router;
    }

    public MediaRouteSelector getMergedSelector() {
        return mergedSelector;
    }

    public CastOptions getOptions() {
        return options;
    }

    String getReceiverApplicationId() {
        return receiverApplicationId;
    }

    void runOnMain(Runnable action) {
        Runnable guarded = () -> {
            if (!destroyed) action.run();
        };
        if (Looper.myLooper() == handler.getLooper()) guarded.run();
        else handler.post(guarded);
    }

    @Override
    public IObjectWrapper getWrappedThis() {
        return ObjectWrapper.wrap(this);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities.add(activity);
        setVisible(true);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        setVisible(true);
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
        startedActivities.remove(activity);
        if (startedActivities.isEmpty()) setVisible(false);
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        startedActivities.remove(activity);
    }
}
