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

package org.microg.gms.cast;

import android.content.Context;
import android.content.IntentFilter;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.mediarouter.media.MediaRouteDescriptor;
import androidx.mediarouter.media.MediaRouteDiscoveryRequest;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouteProviderDescriptor;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class CastMediaRouteProvider extends MediaRouteProvider {
    private static final String TAG = "GmsCastRouteProvider";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, CastDevice> devices = new HashMap<>();
    private final Map<String, String> serviceIds = new HashMap<>();
    private final Map<String, Integer> volumes = new HashMap<>();
    private final Set<String> categories = new LinkedHashSet<>();
    private final Set<String> selectedRoutes = new HashSet<>();
    private final Set<String> foundServices = new HashSet<>();
    private final Set<String> queuedServices = new HashSet<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private final NsdManager nsdManager;
    private final WifiManager.MulticastLock multicastLock;
    private final NsdManager.DiscoveryListener discoveryListener;
    private boolean wanted;
    private boolean activeScan;
    private boolean starting;
    private boolean discovering;
    private boolean stopping;
    private boolean resolving;
    private int generation;

    public CastMediaRouteProvider(Context context) {
        super(context);
        nsdManager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifiManager == null ? null : wifiManager.createMulticastLock(TAG);
        if (multicastLock != null) multicastLock.setReferenceCounted(false);
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) {
                handler.post(() -> {
                    starting = false;
                    discovering = true;
                    Log.d(TAG, "Cast discovery started");
                    updateDiscovery();
                });
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                handler.post(() -> {
                    if (!wanted) return;
                    Log.d(TAG, "Cast service found");
                    foundServices.add(service.getServiceName());
                    if (queuedServices.add(service.getServiceName())) resolveQueue.add(service);
                    resolveNext();
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                handler.post(() -> {
                    String name = service.getServiceName();
                    Log.d(TAG, "Cast service lost");
                    foundServices.remove(name);
                    String id = serviceIds.remove(name);
                    if (id != null && !selectedRoutes.contains(id)) devices.remove(id);
                    publishRoutes();
                });
            }

            @Override
            public void onDiscoveryStopped(String type) {
                handler.post(() -> {
                    stopping = false;
                    discovering = false;
                    updateDiscovery();
                });
            }

            @Override
            public void onStartDiscoveryFailed(String type, int error) {
                handler.post(() -> {
                    starting = false;
                    Log.w(TAG, "Cast discovery failed: " + error);
                    if (wanted) handler.postDelayed(CastMediaRouteProvider.this::updateDiscovery, 5000);
                });
            }

            @Override
            public void onStopDiscoveryFailed(String type, int error) {
                handler.post(() -> {
                    stopping = false;
                    Log.w(TAG, "Unable to stop Cast discovery: " + error);
                    handler.postDelayed(CastMediaRouteProvider.this::updateDiscovery, 5000);
                });
            }
        };
    }

    @Override
    public void onDiscoveryRequestChanged(MediaRouteDiscoveryRequest request) {
        Set<String> requested = new LinkedHashSet<>();
        if (request != null && request.isValid()) {
            for (String category : request.getSelector().getControlCategories()) {
                if (CastMediaControlIntent.CATEGORY_CAST.equals(category) || CastMediaControlIntent.isCategoryForCast(category)) {
                    requested.add(category);
                }
            }
        }
        if (selectedRoutes.isEmpty()) categories.clear();
        categories.addAll(requested);
        wanted = !requested.isEmpty();
        activeScan = wanted && request.isActiveScan();
        Log.d(TAG, "Discovery requested: " + wanted + ", active=" + activeScan + ", categories=" + categories.size());
        if (!wanted) {
            generation++;
            resolveQueue.clear();
            queuedServices.clear();
            foundServices.clear();
            serviceIds.clear();
            devices.keySet().retainAll(selectedRoutes);
        }
        publishRoutes();
        updateDiscovery();
    }

    private void updateDiscovery() {
        if (multicastLock != null) {
            if (activeScan && !multicastLock.isHeld()) multicastLock.acquire();
            else if (!activeScan && multicastLock.isHeld()) multicastLock.release();
        }
        if (Build.VERSION.SDK_INT < 21 || nsdManager == null || starting || stopping) return;
        try {
            if (wanted && !discovering) {
                starting = true;
                nsdManager.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            } else if (!wanted && discovering) {
                stopping = true;
                nsdManager.stopServiceDiscovery(discoveryListener);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            starting = false;
            stopping = false;
            Log.w(TAG, "Unable to update Cast discovery", e);
        }
    }

    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty() || !wanted) return;
        NsdServiceInfo service = resolveQueue.remove();
        int requestGeneration = generation;
        resolving = true;
        NsdManager.ResolveListener listener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int error) {
                handler.post(() -> {
                    resolving = false;
                    if (requestGeneration == generation) queuedServices.remove(info.getServiceName());
                    Log.w(TAG, "Unable to resolve Cast device: " + error);
                    resolveNext();
                });
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                handler.post(() -> {
                    resolving = false;
                    Log.d(TAG, "Cast service resolved: current=" + (requestGeneration == generation)
                            + ", wanted=" + wanted + ", known=" + foundServices.contains(info.getServiceName()));
                    if (requestGeneration == generation) queuedServices.remove(info.getServiceName());
                    if (requestGeneration == generation && wanted && foundServices.contains(info.getServiceName())) addDevice(info);
                    resolveNext();
                });
            }
        };
        try {
            nsdManager.resolveService(service, listener);
        } catch (IllegalArgumentException e) {
            resolving = false;
            queuedServices.remove(service.getServiceName());
            Log.w(TAG, "Invalid Cast discovery response", e);
            resolveNext();
        }
    }

    private void addDevice(NsdServiceInfo info) {
        Map<String, byte[]> attributes = info.getAttributes();
        String id = attribute(attributes, "id", "");
        if (id.isEmpty() || info.getHost() == null || info.getPort() <= 0) {
            Log.w(TAG, "Incomplete Cast service: id=" + !id.isEmpty() + ", host=" + (info.getHost() != null)
                    + ", port=" + info.getPort());
            return;
        }
        String name = info.getServiceName();
        CastDevice device = new CastDevice(id, name, info.getHost(), info.getPort(),
                attribute(attributes, "ve", ""), attribute(attributes, "fn", name), attribute(attributes, "md", ""),
                attribute(attributes, "ic", "/setup/icon.png"), integerAttribute(attributes, "st", 0),
                integerAttribute(attributes, "ca", CastDevice.CAPABILITY_VIDEO_OUT | CastDevice.CAPABILITY_AUDIO_OUT));
        devices.put(id, device);
        serviceIds.put(name, id);
        publishRoutes();
    }

    private static String attribute(Map<String, byte[]> attributes, String key, String fallback) {
        byte[] value = attributes == null ? null : attributes.get(key);
        return value == null ? fallback : new String(value, StandardCharsets.UTF_8);
    }

    private static int integerAttribute(Map<String, byte[]> attributes, String key, int fallback) {
        try {
            return Integer.parseInt(attribute(attributes, key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public RouteController onCreateRouteController(String routeId) {
        CastDevice device = devices.get(routeId);
        return device == null ? null : new CastMediaRouteController(this, routeId, device);
    }

    void setSelected(String routeId, boolean selected) {
        if (selected) selectedRoutes.add(routeId);
        else {
            selectedRoutes.remove(routeId);
            if (!serviceIds.containsValue(routeId)) devices.remove(routeId);
        }
        publishRoutes();
    }

    void setVolume(String routeId, int volume) {
        handler.post(() -> {
            volumes.put(routeId, Math.max(0, Math.min(20, volume)));
            publishRoutes();
        });
    }

    private void publishRoutes() {
        Log.d(TAG, "Publishing " + devices.size() + " Cast routes");
        MediaRouteProviderDescriptor.Builder descriptor = new MediaRouteProviderDescriptor.Builder();
        for (CastDevice device : devices.values()) {
            String id = device.getDeviceId();
            IntentFilter filter = new IntentFilter();
            filter.addCategory(CastMediaControlIntent.CATEGORY_CAST);
            for (String category : categories) filter.addCategory(category);
            Bundle extras = new Bundle();
            device.putInBundle(extras);
            descriptor.addRoute(new MediaRouteDescriptor.Builder(id, device.getFriendlyName())
                    .setDescription(device.getModelName())
                    .addControlFilter(filter)
                    .setDeviceType(device.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT)
                            ? MediaRouter.RouteInfo.DEVICE_TYPE_TV : MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER)
                    .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                    .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
                    .setVolumeMax(20).setVolume(volumes.containsKey(id) ? volumes.get(id) : 0)
                    .setEnabled(true).setExtras(extras)
                    .setConnectionState(selectedRoutes.contains(id) ? MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED
                            : MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED)
                    .build());
        }
        setDescriptor(descriptor.build());
    }
}
