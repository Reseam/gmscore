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

public class MediaRouterCallbackImpl extends IMediaRouterCallback.Stub {
    private final SessionManagerImpl sessionManager;

    public MediaRouterCallbackImpl(CastContextImpl context) {
        sessionManager = context.getSessionManagerImpl();
    }

    @Override
    public void onRouteAdded(String routeId, Bundle extras) {
        sessionManager.onRouteAdded(routeId, extras);
    }

    @Override
    public void onRouteChanged(String routeId, Bundle extras) {
        sessionManager.onRouteAdded(routeId, extras);
    }

    @Override
    public void onRouteRemoved(String routeId, Bundle extras) {
        sessionManager.onRouteRemoved(routeId);
    }

    @Override
    public void onRouteSelected(String routeId, Bundle extras) {
        sessionManager.onRouteSelected(routeId, extras);
    }

    @Override
    public void unknown(String routeId, Bundle extras) {
        sessionManager.onRouteUnselected(routeId);
    }

    @Override
    public void onRouteUnselected(String routeId, Bundle extras, int reason) {
        sessionManager.onRouteUnselected(routeId);
    }

    @Override
    public int getSupportedVersion() {
        return 1;
    }
}
