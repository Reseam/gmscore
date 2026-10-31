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

package org.microg.gms.cast;

import android.util.Log;

import androidx.mediarouter.media.MediaRouteProvider;

import com.google.android.gms.cast.CastDevice;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import su.litvak.chromecast.api.v2.ChromeCast;
import su.litvak.chromecast.api.v2.Status;

public class CastMediaRouteController extends MediaRouteProvider.RouteController {
    private static final String TAG = "GmsCastRouteController";
    private final CastMediaRouteProvider provider;
    private final String routeId;
    private final ChromeCast chromecast;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean released;

    public CastMediaRouteController(CastMediaRouteProvider provider, String routeId, CastDevice device) {
        this.provider = provider;
        this.routeId = routeId;
        chromecast = new ChromeCast(device.getAddress(), device.getServicePort() > 0 ? device.getServicePort() : 8009);
        chromecast.setAutoReconnect(false);
    }

    @Override
    public void onSelect() {
        if (released) return;
        provider.setSelected(routeId, true);
        updateVolume(null, 0);
    }

    @Override
    public void onSetVolume(int volume) {
        updateVolume(Math.max(0, Math.min(20, volume)) / 20f, 0);
    }

    @Override
    public void onUpdateVolume(int delta) {
        updateVolume(null, delta / 20f);
    }

    private void updateVolume(Float volume, float delta) {
        if (released) return;
        executor.execute(() -> {
            try {
                if (!chromecast.isConnected()) {
                    chromecast.connect();
                    chromecast.setRequestTimeout(10000);
                }
                if (volume != null) chromecast.setVolume(volume);
                else if (delta != 0) chromecast.setVolumeByIncrement(delta);
                Status status = chromecast.getStatus();
                if (status.volume != null && status.volume.level != null) {
                    provider.setVolume(routeId, Math.round(status.volume.level * 20));
                }
            } catch (IOException | GeneralSecurityException e) {
                Log.w(TAG, "Error updating Cast volume", e);
            }
        });
    }

    @Override
    public void onUnselect() {
        if (released) return;
        provider.setSelected(routeId, false);
        executor.execute(this::disconnect);
    }

    @Override
    public void onUnselect(int reason) {
        onUnselect();
    }

    @Override
    public void onRelease() {
        if (released) return;
        released = true;
        provider.setSelected(routeId, false);
        executor.execute(this::disconnect);
        executor.shutdown();
    }

    private void disconnect() {
        try {
            chromecast.disconnect();
        } catch (IOException e) {
            Log.w(TAG, "Error closing Cast volume connection", e);
        }
    }
}
