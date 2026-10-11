/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.policy;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.internal.os.BackgroundThread;
import com.oplus.shoulderpressure.IOplusShoulderPressureManager;

import java.util.List;

/** Bridges the OPlus Spruce camera button to the stock camera components. */
final class SpruceButtonController {
    private static final String TAG = "SpruceButtonController";
    private static final String CAMERA_PACKAGE = "com.oplus.camera";
    private static final String SHOULDER_PRESSURE_SERVICE =
            "OplusShoulderPressureManagerService";
    private static final String SHOULDER_PRESSURE_DESCRIPTOR =
            "com.oplus.shoulderpressure.IOplusShoulderPressureManager";
    private static final String KEY_EVENT_OBSERVER_DESCRIPTOR =
            "android.os.IOplusKeyEventObserver";
    private static final String MISC_SERVICE =
            "vendor.oplus.hardware.misc.IOplusMisc/default";
    private static final String MISC_DESCRIPTOR =
            "vendor.oplus.hardware.misc.IOplusMisc";

    private static final int TRANSACTION_REGISTER_KEY_INTERCEPTOR = 1001;
    private static final int TRANSACTION_UNREGISTER_KEY_INTERCEPTOR = 1002;
    private static final int TRANSACTION_REGISTER_KEY_OBSERVER = 1003;
    private static final int TRANSACTION_UNREGISTER_KEY_OBSERVER = 1004;
    private static final int TRANSACTION_DISPATCH_KEY_EVENT = 1;

    private static final int TRANSACTION_MISC_IS_NODE_SUPPORTED = 2;
    private static final int TRANSACTION_MISC_READ_NODE = 3;
    private static final int TRANSACTION_MISC_WRITE_NODE = 4;
    private static final int SPRUCE_DEVICE_ID = 0;
    private static final int SPRUCE_MODE_NODE = 140;

    private static final int KEYCODE_SPRUCE_FIRST = 765;
    private static final int KEYCODE_SPRUCE_DOUBLE_CLICK = 769;
    private static final int KEYCODE_SPRUCE_START_CAMERA = 773;
    private static final int KEYCODE_SPRUCE_LAST = 773;
    private static final int LISTEN_SHOULDER_DOWN_KEY_EVENT = 1 << 25;

    private final ActivityTaskManager mActivityTaskManager;
    private final PowerManager mPowerManager;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final ArrayMap<String, KeyInterceptorRegistration> mKeyInterceptors =
            new ArrayMap<>();
    private final ArrayMap<String, KeyObserverRegistration> mKeyObservers =
            new ArrayMap<>();

    private IBinder mMiscService;
    private boolean mNodeSupportChecked;
    private boolean mNodeSupported;
    private int mServiceRetryCount;
    private volatile boolean mStartCameraWhenWoken;
    private boolean mStarted;
    private int mLastMode = -1;

    private final Runnable mUpdateModeRunnable = this::updateMode;

    private final IOplusShoulderPressureManager.Stub mShoulderPressureService =
            new IOplusShoulderPressureManager.Stub() {
                @Override
                public String readShoulderPressureNodeFile(int nodeFlag) {
                    return readNode(0, nodeFlag);
                }

                @Override
                public String readShoulderPressureNodeFileByDevice(int deviceId, int nodeFlag) {
                    return readNode(deviceId, nodeFlag);
                }

                @Override
                public boolean writeShoulderPressureNodeFile(int nodeFlag, String info) {
                    return writeNode(0, nodeFlag, info);
                }

                @Override
                public boolean writeShoulderPressureNodeFileByDevice(
                        int deviceId, int nodeFlag, String info) {
                    return writeNode(deviceId, nodeFlag, info);
                }

                @Override
                public boolean isShoulderPressureNodeSupport(int deviceId, int nodeFlag) {
                    return isNodeSupported(deviceId, nodeFlag);
                }

                @Override
                public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    switch (code) {
                        case TRANSACTION_REGISTER_KEY_INTERCEPTOR: {
                            data.enforceInterface(SHOULDER_PRESSURE_DESCRIPTOR);
                            final String key = data.readString();
                            final IBinder observer = data.readStrongBinder();
                            final int size = data.readInt();
                            final ArrayMap<Integer, Integer> configs = new ArrayMap<>(size);
                            for (int i = 0; i < size; i++) {
                                configs.put(data.readInt(), data.readInt());
                            }
                            data.enforceNoDataAvail();
                            final boolean registered =
                                    registerKeyInterceptor(key, observer, configs);
                            reply.writeNoException();
                            reply.writeBoolean(registered);
                            return true;
                        }
                        case TRANSACTION_UNREGISTER_KEY_INTERCEPTOR: {
                            data.enforceInterface(SHOULDER_PRESSURE_DESCRIPTOR);
                            final String key = data.readString();
                            data.enforceNoDataAvail();
                            final boolean unregistered = unregisterKeyInterceptor(key);
                            reply.writeNoException();
                            reply.writeBoolean(unregistered);
                            return true;
                        }
                        case TRANSACTION_REGISTER_KEY_OBSERVER: {
                            data.enforceInterface(SHOULDER_PRESSURE_DESCRIPTOR);
                            final String key = data.readString();
                            final IBinder observer = data.readStrongBinder();
                            final int listenFlag = data.readInt();
                            data.enforceNoDataAvail();
                            final boolean registered =
                                    registerKeyObserver(key, observer, listenFlag);
                            reply.writeNoException();
                            reply.writeBoolean(registered);
                            return true;
                        }
                        case TRANSACTION_UNREGISTER_KEY_OBSERVER: {
                            data.enforceInterface(SHOULDER_PRESSURE_DESCRIPTOR);
                            final String key = data.readString();
                            data.enforceNoDataAvail();
                            final boolean unregistered = unregisterKeyObserver(key);
                            reply.writeNoException();
                            reply.writeBoolean(unregistered);
                            return true;
                        }
                        default:
                            return super.onTransact(code, data, reply, flags);
                    }
                }
            };

    private final IBinder.DeathRecipient mMiscDeathRecipient = () -> {
        Slog.w(TAG, "OPlus Misc HAL died");
        mHandler.post(() -> {
            mMiscService = null;
            mNodeSupportChecked = false;
            mNodeSupported = false;
            mLastMode = -1;
            scheduleModeUpdate(1000);
        });
    };

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            scheduleModeUpdate(100);
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            scheduleModeUpdate(100);
        }

        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            scheduleModeUpdate(100);
        }
    };

    SpruceButtonController(Context context) {
        mActivityTaskManager = context.getSystemService(ActivityTaskManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        if (ServiceManager.checkService(SHOULDER_PRESSURE_SERVICE) == null) {
            ServiceManager.addService(SHOULDER_PRESSURE_SERVICE,
                    mShoulderPressureService, false, 8);
            Slog.i(TAG, "Published " + SHOULDER_PRESSURE_SERVICE);
        }
        if (mActivityTaskManager != null) {
            mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
        }
        scheduleModeUpdate(1500);
    }

    void onScreenOff() {
        if (mStarted) {
            mHandler.post(() -> setMode(0));
        }
    }

    void onScreenOn() {
        if (!mStarted) {
            return;
        }
        if (mStartCameraWhenWoken) {
            mStartCameraWhenWoken = false;
            final long now = SystemClock.uptimeMillis();
            dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KEYCODE_SPRUCE_START_CAMERA, 0));
            dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                    KEYCODE_SPRUCE_START_CAMERA, 0));
        }
        scheduleModeUpdate(100);
    }

    boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KEYCODE_SPRUCE_DOUBLE_CLICK
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && mPowerManager != null && !mPowerManager.isInteractive()) {
            mStartCameraWhenWoken = true;
        }

        final IBinder[] observers;
        synchronized (this) {
            final int keyCode = event.getKeyCode();
            int count = 0;
            for (int i = 0; i < mKeyInterceptors.size(); i++) {
                if (mKeyInterceptors.valueAt(i).mConfigs.containsKey(keyCode)) {
                    count++;
                }
            }
            for (int i = 0; i < mKeyObservers.size(); i++) {
                if (mKeyObservers.valueAt(i).matches(keyCode)) {
                    count++;
                }
            }
            if (count == 0) {
                return false;
            }
            observers = new IBinder[count];
            int index = 0;
            for (int i = 0; i < mKeyInterceptors.size(); i++) {
                final KeyInterceptorRegistration registration = mKeyInterceptors.valueAt(i);
                if (registration.mConfigs.containsKey(keyCode)) {
                    observers[index++] = registration.mObserver;
                }
            }
            for (int i = 0; i < mKeyObservers.size(); i++) {
                final KeyObserverRegistration registration = mKeyObservers.valueAt(i);
                if (registration.matches(keyCode)) {
                    observers[index++] = registration.mObserver;
                }
            }
        }

        boolean handled = false;
        for (IBinder observer : observers) {
            final Parcel data = Parcel.obtain(observer);
            try {
                data.writeInterfaceToken(KEY_EVENT_OBSERVER_DESCRIPTOR);
                data.writeTypedObject(event, 0);
                handled |= observer.transact(TRANSACTION_DISPATCH_KEY_EVENT, data, null,
                        IBinder.FLAG_ONEWAY);
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "Unable to dispatch Spruce key event", e);
            } finally {
                data.recycle();
            }
        }
        return handled;
    }

    private synchronized boolean registerKeyInterceptor(String key, IBinder observer,
            ArrayMap<Integer, Integer> configs) {
        if (key == null || key.isEmpty() || observer == null || configs.isEmpty()
                || mKeyInterceptors.containsKey(key)) {
            return false;
        }
        final KeyInterceptorRegistration registration =
                new KeyInterceptorRegistration(key, observer, configs);
        try {
            observer.linkToDeath(registration, 0);
            mKeyInterceptors.put(key, registration);
            Slog.i(TAG, "Registered key interceptor " + key + ": " + configs.keySet());
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    private synchronized boolean unregisterKeyInterceptor(String key) {
        final KeyInterceptorRegistration registration = mKeyInterceptors.remove(key);
        if (registration == null) {
            return false;
        }
        registration.mObserver.unlinkToDeath(registration, 0);
        Slog.i(TAG, "Unregistered key interceptor " + key);
        return true;
    }

    private synchronized boolean registerKeyObserver(String key, IBinder observer,
            int listenFlag) {
        if (key == null || key.isEmpty() || observer == null || mKeyObservers.containsKey(key)) {
            return false;
        }
        final KeyObserverRegistration registration =
                new KeyObserverRegistration(key, observer, listenFlag);
        try {
            observer.linkToDeath(registration, 0);
            mKeyObservers.put(key, registration);
            Slog.i(TAG, "Registered key observer " + key + ": " + listenFlag);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    private synchronized boolean unregisterKeyObserver(String key) {
        final KeyObserverRegistration registration = mKeyObservers.remove(key);
        if (registration == null) {
            return false;
        }
        registration.mObserver.unlinkToDeath(registration, 0);
        return true;
    }

    private final class KeyInterceptorRegistration implements IBinder.DeathRecipient {
        final String mKey;
        final IBinder mObserver;
        final ArrayMap<Integer, Integer> mConfigs;

        KeyInterceptorRegistration(String key, IBinder observer,
                ArrayMap<Integer, Integer> configs) {
            mKey = key;
            mObserver = observer;
            mConfigs = new ArrayMap<>(configs);
        }

        @Override
        public void binderDied() {
            synchronized (SpruceButtonController.this) {
                if (mKeyInterceptors.get(mKey) == this) {
                    mKeyInterceptors.remove(mKey);
                }
            }
        }
    }

    private final class KeyObserverRegistration implements IBinder.DeathRecipient {
        final String mKey;
        final IBinder mObserver;
        final int mListenFlag;

        KeyObserverRegistration(String key, IBinder observer, int listenFlag) {
            mKey = key;
            mObserver = observer;
            mListenFlag = listenFlag;
        }

        boolean matches(int keyCode) {
            return keyCode >= KEYCODE_SPRUCE_FIRST && keyCode <= KEYCODE_SPRUCE_LAST
                    && (mListenFlag & LISTEN_SHOULDER_DOWN_KEY_EVENT) != 0;
        }

        @Override
        public void binderDied() {
            synchronized (SpruceButtonController.this) {
                if (mKeyObservers.get(mKey) == this) {
                    mKeyObservers.remove(mKey);
                }
            }
        }
    }

    private void scheduleModeUpdate(long delayMillis) {
        mHandler.removeCallbacks(mUpdateModeRunnable);
        mHandler.postDelayed(mUpdateModeRunnable, delayMillis);
    }

    private void updateMode() {
        setMode(mPowerManager != null && mPowerManager.isInteractive() && isCameraFocused() ? 1 : 0);
    }

    private boolean isCameraFocused() {
        if (mActivityTaskManager == null) {
            return false;
        }
        final List<ActivityManager.RunningTaskInfo> tasks;
        try {
            tasks = mActivityTaskManager.getTasks(10);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to query foreground task", e);
            return false;
        }
        for (ActivityManager.RunningTaskInfo task : tasks) {
            final ComponentName topActivity = task.topActivity;
            if (topActivity != null && CAMERA_PACKAGE.equals(topActivity.getPackageName())
                    && task.isFocused) {
                return true;
            }
        }
        return false;
    }

    private void setMode(int mode) {
        if (mode == mLastMode || !ensureNodeSupported()) {
            return;
        }
        try {
            final int result = transactWriteNode(SPRUCE_DEVICE_ID, SPRUCE_MODE_NODE,
                    Integer.toString(mode));
            if (result < 0) {
                Slog.e(TAG, "Failed to set Spruce mode " + mode + ": " + result);
                scheduleModeUpdate(1000);
                return;
            }
            mLastMode = mode;
            Slog.i(TAG, "Spruce mode=" + mode + " (node="
                    + transactReadNode(SPRUCE_DEVICE_ID, SPRUCE_MODE_NODE) + ")");
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Failed to set Spruce mode " + mode, e);
            clearService();
            scheduleModeUpdate(1000);
        }
    }

    private boolean ensureNodeSupported() {
        if (mNodeSupportChecked) {
            return mNodeSupported;
        }
        if (getMiscService() == null) {
            if (++mServiceRetryCount <= 10) {
                scheduleModeUpdate(1000);
            }
            return false;
        }
        mServiceRetryCount = 0;
        try {
            mNodeSupported = transactIsNodeSupported(SPRUCE_DEVICE_ID, SPRUCE_MODE_NODE) > 0;
            mNodeSupportChecked = true;
            if (!mNodeSupported) {
                Slog.e(TAG, "OPlus Misc HAL does not expose Spruce node " + SPRUCE_MODE_NODE);
            }
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Failed to query Spruce node support", e);
            clearService();
            scheduleModeUpdate(1000);
        }
        return mNodeSupported;
    }

    private IBinder getMiscService() {
        if (mMiscService != null && mMiscService.isBinderAlive()) {
            return mMiscService;
        }
        final IBinder service = ServiceManager.checkService(MISC_SERVICE);
        if (service == null) {
            Slog.w(TAG, "OPlus Misc HAL is not available yet");
            return null;
        }
        try {
            service.linkToDeath(mMiscDeathRecipient, 0);
            mMiscService = service;
            Slog.i(TAG, "Connected to OPlus Misc HAL; enabling Spruce controller");
            return service;
        } catch (RemoteException e) {
            Slog.w(TAG, "OPlus Misc HAL died while connecting", e);
            return null;
        }
    }

    private void clearService() {
        if (mMiscService != null) {
            mMiscService.unlinkToDeath(mMiscDeathRecipient, 0);
        }
        mMiscService = null;
        mNodeSupportChecked = false;
        mNodeSupported = false;
        mLastMode = -1;
    }

    private boolean isNodeSupported(int deviceId, int nodeFlag) {
        if (getMiscService() == null) {
            return false;
        }
        try {
            return transactIsNodeSupported(deviceId, nodeFlag) > 0;
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Failed to query node " + nodeFlag, e);
            clearService();
            return false;
        }
    }

    private String readNode(int deviceId, int nodeFlag) {
        if (getMiscService() == null) {
            return null;
        }
        try {
            return transactReadNode(deviceId, nodeFlag);
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Failed to read node " + nodeFlag, e);
            clearService();
            return null;
        }
    }

    private boolean writeNode(int deviceId, int nodeFlag, String value) {
        if (getMiscService() == null) {
            return false;
        }
        try {
            return transactWriteNode(deviceId, nodeFlag, value) >= 0;
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Failed to write node " + nodeFlag, e);
            clearService();
            return false;
        }
    }

    private int transactIsNodeSupported(int deviceId, int nodeFlag) throws RemoteException {
        final Parcel data = Parcel.obtain(mMiscService);
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MISC_DESCRIPTOR);
            data.writeInt(deviceId);
            data.writeInt(nodeFlag);
            if (!mMiscService.transact(TRANSACTION_MISC_IS_NODE_SUPPORTED, data, reply, 0)) {
                throw new RemoteException("isMiscNodeSupport is unimplemented");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private String transactReadNode(int deviceId, int nodeFlag) throws RemoteException {
        final Parcel data = Parcel.obtain(mMiscService);
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MISC_DESCRIPTOR);
            data.writeInt(deviceId);
            data.writeInt(nodeFlag);
            if (!mMiscService.transact(TRANSACTION_MISC_READ_NODE, data, reply, 0)) {
                throw new RemoteException("miscReadNodeFile is unimplemented");
            }
            reply.readException();
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private int transactWriteNode(int deviceId, int nodeFlag, String value)
            throws RemoteException {
        final Parcel data = Parcel.obtain(mMiscService);
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MISC_DESCRIPTOR);
            data.writeInt(deviceId);
            data.writeInt(nodeFlag);
            data.writeString(value);
            if (!mMiscService.transact(TRANSACTION_MISC_WRITE_NODE, data, reply, 0)) {
                throw new RemoteException("miscWriteNodeFile is unimplemented");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
