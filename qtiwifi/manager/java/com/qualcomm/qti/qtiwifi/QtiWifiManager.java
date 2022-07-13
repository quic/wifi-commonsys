/* Copyright (c) 2021-2022 Qualcomm Innovation Center, Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted (subject to the limitations in the
 * disclaimer below) provided that the following conditions are met:
 *
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *
 *   * Neither the name of Qualcomm Innovation Center nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE
 * GRANTED BY THIS LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT
 * HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.qualcomm.qti.qtiwifi;

import android.content.Context;
import android.os.Handler;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import java.util.List;

public class QtiWifiManager {
    private static final String TAG = "QtiWifiManager";
    private static ApplicationBinderCallback mApplicationCallback = null;
    private static Context mContext;
    private static boolean mServiceAlreadyBound = false;
    private static IQtiWifiManager mUniqueInstance = null;
    IQtiWifiManager mService;

    private QtiWifiManager(Context context, IQtiWifiManager service) {
        mContext = context;
        mService = service;
        Log.i(TAG, "QtiWifiManager created");
    }

    public static void initialize(Context context, ApplicationBinderCallback cb) {
        try {
            bindService(context);
        } catch (ServiceFailedToBindException e) {
            Log.e(TAG, "ServiceFailedToBindException received");
        }
        mApplicationCallback = cb;
        mContext = context;
    }

    private static void bindService(Context context)
        throws ServiceFailedToBindException {
        if (!mServiceAlreadyBound  || mUniqueInstance == null) {
            Log.d(TAG, "bindService- !mServiceAlreadyBound  || uniqueInstance == null");
            Intent serviceIntent = new Intent("com.qualcomm.qti.server.qtiwifi.QtiWifiService");
            serviceIntent.setPackage("com.qualcomm.qti.server.qtiwifi");
            if (!context.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE)) {
                Log.e(TAG,"Failed to connect to Provider service");
                throw new ServiceFailedToBindException("Failed to connect to Provider service");
            }
        }
    }

    public static void unbindService(Context context) {
        if(mServiceAlreadyBound) {
            context.unbindService(mConnection);
            mServiceAlreadyBound = false;
            mUniqueInstance = null;
        }
    }

    protected static ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "Connection object created");
            mServiceAlreadyBound = true;
            if (service == null) {
                Log.e(TAG, "qtiwifi service not available");
                return;
            }
            mUniqueInstance = IQtiWifiManager.Stub.asInterface(service);
            new QtiWifiManager(mContext, mUniqueInstance);
            mApplicationCallback.onAvailable(new QtiWifiManager(mContext, mUniqueInstance));
        }
        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Remote service disconnected");
            mServiceAlreadyBound = false;
            mUniqueInstance = null;
        }
    };

    public static class ServiceFailedToBindException extends Exception {
        public static final long serialVersionUID = 1L;

        private ServiceFailedToBindException(String inString) {
            super(inString);
        }
    }

    public interface ApplicationBinderCallback {
        public abstract void onAvailable(QtiWifiManager manager);
    }

    /**
     * Base class for Csi callback. Should be extended by applications and set when calling
     * {@link QtiWifiManager#registerCsiCallback(CsiCallback, Handler)}.
     *
     */
    public interface CsiCallback {
        public abstract void onCsiUpdate(byte[] info);
    }

    /**
     * Callback proxy for CsiCallback objects.
     *
     */

    private static class CsiCallbackProxy extends ICsiCallback.Stub {
        private final Handler mHandler;
        private final CsiCallback mCallback;

        CsiCallbackProxy(Looper looper, CsiCallback callback) {
            mHandler = new Handler(looper);
            mCallback = callback;
        }

        @Override
        public void onCsiUpdate(byte[] info) throws RemoteException {
            mHandler.post(() -> {
                mCallback.onCsiUpdate(info);
            });
        }
    }

    private void registerCsiCallback(CsiCallback callback, Handler handler) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "registerCsiCallback: callback=" + callback + ", handler=" + handler);

        Looper looper = (handler == null) ? mContext.getMainLooper() : handler.getLooper();
        Binder binder = new Binder();
        try {
            mService.registerCsiCallback(binder, new CsiCallbackProxy(looper, callback),
                    callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allow callers to unregister a previously registered callback. After calling this method,
     * applications will no longer receive csi events.
     *
     * @param callback Callback to unregister for csi events
     *
     */
    private void unregisterCsiCallback(CsiCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "unregisterCsiCallback: callback=" + callback);

        try {
            mService.unregisterCsiCallback(callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean startCsi(CsiCallback callback, Handler handler) {
        registerCsiCallback(callback, handler);
        try {
            mService.startCsi();
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "startCsi: " + e);
            return false;
        }
    }

    public boolean stopCsi(CsiCallback callback) {
        try {
            mService.stopCsi();
        } catch (RemoteException e) {
            Log.e(TAG, "stopCsi: " + e);
            return false;
        }
        unregisterCsiCallback(callback);
        return true;
    }
}
