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

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.ApplicationStatus;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastDeviceStatus;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.JoinOptions;
import com.google.android.gms.cast.LaunchOptions;
import com.google.android.gms.cast.internal.ICastDeviceController;
import com.google.android.gms.cast.internal.ICastDeviceControllerListener;
import com.google.android.gms.common.internal.BinderWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import su.litvak.chromecast.api.v2.Application;
import su.litvak.chromecast.api.v2.ChromeCast;
import su.litvak.chromecast.api.v2.ChromeCastConnectionEvent;
import su.litvak.chromecast.api.v2.ChromeCastConnectionEventListener;
import su.litvak.chromecast.api.v2.ChromeCastRawMessage;
import su.litvak.chromecast.api.v2.ChromeCastRawMessageListener;
import su.litvak.chromecast.api.v2.ChromeCastSpontaneousEvent;
import su.litvak.chromecast.api.v2.ChromeCastSpontaneousEventListener;
import su.litvak.chromecast.api.v2.Namespace;
import su.litvak.chromecast.api.v2.Status;

public class CastDeviceControllerImpl extends ICastDeviceController.Stub implements
        ChromeCastConnectionEventListener, ChromeCastSpontaneousEventListener, ChromeCastRawMessageListener {
    private static final String TAG = "GmsCastDeviceController";

    private final String packageName;
    private final ChromeCast chromecast;
    private final Set<String> namespaces = new HashSet<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private ICastDeviceControllerListener listener;
    private IBinder.DeathRecipient deathRecipient;
    private volatile String sessionId;
    private boolean connected;

    public CastDeviceControllerImpl(Context context, String packageName, Bundle extras) {
        this.packageName = packageName;
        CastDevice device = CastDevice.getFromBundle(extras);
        if (device == null || TextUtils.isEmpty(device.getAddress())) {
            throw new IllegalArgumentException("Missing Cast device");
        }
        chromecast = new ChromeCast(device.getAddress(), device.getServicePort() > 0 ? device.getServicePort() : 8009);
        chromecast.setAutoReconnect(false);
        chromecast.registerListener(this);
        chromecast.registerRawMessageListener(this);
        chromecast.registerConnectionListener(this);
        executor.allowCoreThreadTimeOut(true);
        extras.setClassLoader(BinderWrapper.class.getClassLoader());
        BinderWrapper wrapper = extras.getParcelable("listener");
        if (wrapper != null) addListener(ICastDeviceControllerListener.Stub.asInterface(wrapper.binder));
    }

    @Override
    public void addListener(ICastDeviceControllerListener listener) {
        executor.execute(() -> {
            clearListener();
            if (listener == null) return;
            IBinder binder = listener.asBinder();
            IBinder.DeathRecipient recipient = () -> executor.execute(() -> {
                if (this.listener != null && this.listener.asBinder().equals(binder)) {
                    clearListener();
                    disconnectDevice(CastStatusCodes.SUCCESS);
                }
            });
            try {
                binder.linkToDeath(recipient, 0);
                this.listener = listener;
                deathRecipient = recipient;
            } catch (RemoteException e) {
                disconnectDevice(CastStatusCodes.SUCCESS);
            }
        });
    }

    @Override
    public void removeListener() {
        executor.execute(() -> {
            clearListener();
            disconnectDevice(CastStatusCodes.SUCCESS);
        });
    }

    private void clearListener() {
        if (listener != null && deathRecipient != null) {
            listener.asBinder().unlinkToDeath(deathRecipient, 0);
        }
        listener = null;
        deathRecipient = null;
    }

    @Override
    public void connect() {
        executor.execute(() -> {
            try {
                connectDevice();
                Status status = chromecast.getStatus();
                Log.d(TAG, "Cast device connected");
                notifyListener(callback -> callback.onConnectedWithResult(CastStatusCodes.SUCCESS));
                updateStatus(status);
            } catch (IOException | GeneralSecurityException e) {
                Log.w(TAG, "Error connecting to Cast device", e);
                notifyListener(callback -> callback.onConnectedWithResult(CastStatusCodes.NETWORK_ERROR));
                disconnectDevice(CastStatusCodes.NETWORK_ERROR);
            }
        });
    }

    private void connectDevice() throws IOException, GeneralSecurityException {
        if (listener == null) throw new IOException("Cast listener disconnected");
        if (!chromecast.isConnected()) {
            chromecast.connect();
            chromecast.setRequestTimeout(10000);
        }
        connected = true;
    }

    @Override
    public void disconnect() {
        executor.execute(() -> disconnectDevice(CastStatusCodes.SUCCESS));
    }

    private void disconnectDevice(int reason) {
        boolean wasConnected = connected;
        connected = false;
        sessionId = null;
        namespaces.clear();
        try {
            chromecast.disconnect();
        } catch (IOException e) {
            Log.w(TAG, "Error disconnecting Cast device", e);
        }
        if (wasConnected) notifyListener(callback -> callback.onDisconnected(reason));
    }

    @Override
    public void sendMessage(String namespace, String message, long requestId) {
        executor.execute(() -> {
            if (!isValidNamespace(namespace) || message == null) {
                notifyListener(callback -> callback.onSendMessageFailure(namespace, requestId, CastStatusCodes.INVALID_REQUEST));
                return;
            }
            if (message.getBytes(StandardCharsets.UTF_8).length > 65536) {
                notifyListener(callback -> callback.onSendMessageFailure(namespace, requestId, CastStatusCodes.MESSAGE_TOO_LARGE));
                return;
            }
            try {
                connectDevice();
                Log.d(TAG, "Sending message on " + namespace + ", requestId=" + requestId);
                chromecast.sendRawRequest(namespace, message, requestId);
                notifyListener(callback -> callback.onSendMessageSuccess(namespace, requestId));
            } catch (IOException | GeneralSecurityException e) {
                Log.w(TAG, "Error sending Cast message", e);
                notifyListener(callback -> callback.onSendMessageFailure(namespace, requestId, CastStatusCodes.NETWORK_ERROR));
            }
        });
    }

    private static boolean isValidNamespace(String namespace) {
        return namespace != null && namespace.startsWith("urn:x-cast:") && namespace.length() > 11 && namespace.length() <= 128;
    }

    @Override
    public void registerNamespace(String namespace) {
        executor.execute(() -> {
            if (isValidNamespace(namespace)) namespaces.add(namespace);
        });
    }

    @Override
    public void unregisterNamespace(String namespace) {
        executor.execute(() -> namespaces.remove(namespace));
    }

    @Override
    public void launchApplication(String applicationId, LaunchOptions options) {
        executor.execute(() -> {
            if (TextUtils.isEmpty(applicationId)) {
                notifyListener(callback -> callback.onApplicationConnectionFailure(CastStatusCodes.INVALID_REQUEST));
                return;
            }
            try {
                connectDevice();
                Application running = chromecast.getRunningApp();
                boolean reuse = running != null && applicationId.equals(running.id) && (options == null || !options.getRelaunchIfRunning());
                if (!reuse && running != null && applicationId.equals(running.id)) chromecast.stopSession(running.sessionId);
                Application application = reuse ? running : chromecast.launchApp(applicationId);
                if (application == null || !applicationId.equals(application.id)) {
                    notifyListener(callback -> callback.onApplicationConnectionFailure(CastStatusCodes.APPLICATION_NOT_FOUND));
                    return;
                }
                applicationConnected(application, !reuse);
            } catch (IOException | GeneralSecurityException e) {
                Log.w(TAG, "Error launching Cast application", e);
                notifyListener(callback -> callback.onApplicationConnectionFailure(CastStatusCodes.NETWORK_ERROR));
            }
        });
    }

    @Override
    public void joinApplication(String applicationId, String sessionId, JoinOptions options) {
        executor.execute(() -> {
            try {
                connectDevice();
                Application application = chromecast.getRunningApp();
                if (application == null || (!TextUtils.isEmpty(applicationId) && !applicationId.equals(application.id))
                        || (!TextUtils.isEmpty(sessionId) && !sessionId.equals(application.sessionId))) {
                    notifyListener(callback -> callback.onApplicationConnectionFailure(CastStatusCodes.APPLICATION_NOT_RUNNING));
                    return;
                }
                applicationConnected(application, false);
            } catch (IOException | GeneralSecurityException e) {
                Log.w(TAG, "Error joining Cast application", e);
                notifyListener(callback -> callback.onApplicationConnectionFailure(CastStatusCodes.NETWORK_ERROR));
            }
        });
    }

    private void applicationConnected(Application application, boolean launched) {
        sessionId = application.sessionId;
        Log.d(TAG, "Application connected: " + application.id + ", launched=" + launched);
        ApplicationMetadata metadata = createMetadata(application);
        notifyListener(callback -> callback.onApplicationConnectionSuccess(metadata, application.statusText, application.sessionId, launched));
    }

    @Override
    public void leaveApplication() {
        executor.execute(() -> {
            try {
                connected = false;
                chromecast.disconnect();
                connectDevice();
                sessionId = null;
                namespaces.clear();
                notifyListener(callback -> callback.onApplicationLeaveResult(CastStatusCodes.SUCCESS));
            } catch (IOException | GeneralSecurityException e) {
                Log.w(TAG, "Error leaving Cast application", e);
                notifyListener(callback -> callback.onApplicationLeaveResult(CastStatusCodes.NETWORK_ERROR));
                disconnectDevice(CastStatusCodes.NETWORK_ERROR);
            }
        });
    }

    @Override
    public void stopApplication(String sessionId) {
        executor.execute(() -> {
            try {
                connectDevice();
                chromecast.stopSession(sessionId);
                notifyListener(callback -> callback.onApplicationStopResult(CastStatusCodes.SUCCESS));
                applicationDisconnected(CastStatusCodes.SUCCESS);
            } catch (IOException | GeneralSecurityException e) {
                Log.w(TAG, "Error stopping Cast application", e);
                notifyListener(callback -> callback.onApplicationStopResult(CastStatusCodes.NETWORK_ERROR));
            }
        });
    }

    private void applicationDisconnected(int reason) {
        if (sessionId == null) return;
        sessionId = null;
        namespaces.clear();
        notifyListener(callback -> callback.onApplicationDisconnected(reason));
    }

    @Override
    public void connectionEventReceived(ChromeCastConnectionEvent event) {
        if (!event.isConnected()) {
            executor.execute(() -> {
                if (connected && !chromecast.isConnected()) disconnectDevice(CastStatusCodes.NETWORK_ERROR);
            });
        }
    }

    @Override
    public void spontaneousEventReceived(ChromeCastSpontaneousEvent event) {
        String eventSessionId = sessionId;
        executor.execute(() -> {
            if (!connected) return;
            switch (event.getType()) {
                case STATUS:
                    updateStatus((Status) event.getData());
                    break;
                case CLOSE:
                    if (TextUtils.equals(sessionId, eventSessionId)) applicationDisconnected(CastStatusCodes.SUCCESS);
                    break;
                default:
                    break;
            }
        });
    }

    private void updateStatus(Status status) {
        Application application = status.getRunningApp();
        if (application != null) {
            notifyListener(callback -> callback.onApplicationStatusChanged(new ApplicationStatus(application.statusText)));
        }
        double volume = status.volume != null && status.volume.level != null ? status.volume.level : 0;
        boolean muted = status.volume != null && Boolean.TRUE.equals(status.volume.muted);
        CastDeviceStatus deviceStatus = new CastDeviceStatus(volume, muted, status.activeInput ? 1 : 0, createMetadata(application), status.standBy ? 1 : 0);
        notifyListener(callback -> callback.onDeviceStatusChanged(deviceStatus));
    }

    private ApplicationMetadata createMetadata(Application application) {
        if (application == null) return null;
        ApplicationMetadata metadata = new ApplicationMetadata();
        metadata.applicationId = application.id;
        metadata.name = application.name;
        metadata.images = new ArrayList<>();
        metadata.namespaces = new ArrayList<>();
        if (application.namespaces != null) {
            for (Namespace namespace : application.namespaces) metadata.namespaces.add(namespace.name);
        }
        metadata.senderAppIdentifier = packageName;
        return metadata;
    }

    @Override
    public void rawMessageReceived(ChromeCastRawMessage message, Long requestId) {
        executor.execute(() -> {
            if (!connected || !namespaces.contains(message.getNamespace())) return;
            Log.d(TAG, "Received message on " + message.getNamespace());
            switch (message.getPayloadType()) {
                case STRING:
                    notifyListener(callback -> callback.onTextMessageReceived(message.getNamespace(), message.getPayloadUtf8()));
                    break;
                case BINARY:
                    notifyListener(callback -> callback.onBinaryMessageReceived(message.getNamespace(), message.getPayloadBinary()));
                    break;
                default:
                    break;
            }
        });
    }

    private void notifyListener(ListenerCall call) {
        if (listener == null) return;
        try {
            call.run(listener);
        } catch (RemoteException e) {
            Log.w(TAG, "Cast listener disconnected", e);
            clearListener();
            disconnectDevice(CastStatusCodes.SUCCESS);
        }
    }

    private interface ListenerCall {
        void run(ICastDeviceControllerListener listener) throws RemoteException;
    }
}
