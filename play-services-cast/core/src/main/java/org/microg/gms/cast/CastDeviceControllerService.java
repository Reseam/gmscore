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

package org.microg.gms.cast;

import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.common.Feature;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.internal.ConnectionInfo;
import com.google.android.gms.common.internal.GetServiceRequest;
import com.google.android.gms.common.internal.IGmsCallbacks;

import org.microg.gms.BaseService;
import org.microg.gms.common.GmsService;

public class CastDeviceControllerService extends BaseService {
    private static final String TAG = "GmsCastDeviceControllerSvc";
    private static final Feature[] DEVICE_FEATURES = {new Feature("cxless_client_minimal", 1)};
    private static final Feature[] SERVICE_FEATURES = {new Feature("module_flag_control", 1)};

    public CastDeviceControllerService() {
        super(TAG, GmsService.CAST, GmsService.CAST_API);
    }

    @Override
    public void handleServiceRequest(IGmsCallbacks callback, GetServiceRequest request, GmsService service) throws RemoteException {
        ConnectionInfo info = new ConnectionInfo();
        if (service == GmsService.CAST_API) {
            info.features = SERVICE_FEATURES;
            callback.onPostInitCompleteWithConnectionInfo(CommonStatusCodes.SUCCESS, new CastServiceImpl(), info);
            return;
        }
        try {
            info.features = DEVICE_FEATURES;
            callback.onPostInitCompleteWithConnectionInfo(CommonStatusCodes.SUCCESS,
                    new CastDeviceControllerImpl(this, request.packageName, request.extras), info);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid Cast device request", e);
            callback.onPostInitComplete(CommonStatusCodes.DEVELOPER_ERROR, null, null);
        }
    }
}
