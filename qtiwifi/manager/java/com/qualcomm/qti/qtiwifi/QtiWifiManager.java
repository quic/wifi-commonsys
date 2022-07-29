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
import android.content.pm.PackageManager;
import java.util.List;
import com.qualcomm.qti.qtiwifi.ThermalData;

/**
 * This Class provides few aspects of managing Wi-Fi specific operation after Wi-Fi is turned on (Wi-Fi modules are initialized).
 * This is primarily to be used by privileged/OEM application with "com.qualcomm.permission.QTI_WIFI" permission.
 *
 * How to use QtiWifiManager from external application:
 *  1. Import WiFiManager and QtiWifiManager
 *      import android.net.wifi.WifiManager;
 *      import com.qualcomm.qti.qtiwifi.QtiWifiManager;
 *  2. Create an ApplicationBinder context with QtiWifiManager.
 *      private QtiWifiManager.ApplicationBinderCallback mApplicationCallback
 *               = new QtiWifiManager.ApplicationBinderCallback() {
 *               @Override
 *               public void onAvailable(QtiWifiManager qtiWifiManager) {
 *                               mUniqueInstance = qtiWifiManager;
 *               }
 *      };
 *
 *  3. while doing onCreate() for application initialize QtiWifiManager:
 *      QtiWifiManager.initialize(this, mApplicationCallback);
 *
 *  4. To destroy the service while APK is killed:
 *      QtiWifiManager.unbindService(context);
 */
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

    /**
     * API to initialize QtiWifiManager.
     * This is an entry point for using QtiWifiManager.
     * To initialize and get the instance of QtiWifiManager, caller need to pass
     * the QtiWifiManager.ApplicationBinderCallback instance.
     * QtiWifiManager instance is received via ApplicationBinderCallback.onAvailable()
     *
     * @param context the application context
     * @param cb ApplicationBinderCallback
     *
     */
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

    /**
     * API to destroy the service while APK is killed.
     * To remove/delete the QtiWifiManager instance.
     * This is usually done when Application no longer want to use QtiWifiManager.
     *
     * @param context the application context
     *
     */
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

    /**
     * Base class for Application binder callback
     *
     */
    public interface ApplicationBinderCallback {
        public abstract void onAvailable(QtiWifiManager manager);
    }

    public List<String> getAvailableInterfaces() {
        try {
            return mService.getAvailableInterfaces();
        } catch (RemoteException e) {
            Log.e(TAG, "getAvailableInterfaces: " + e);
            return null;
        }
    }

    private static class VendorEventCallbackProxy extends IVendorEventCallback.Stub {
        private final Handler mHandler;
        private final VendorEventCallback mCallback;

        VendorEventCallbackProxy(Looper looper, VendorEventCallback callback) {
            mHandler = new Handler(looper);
            mCallback = callback;
        }

        @Override
        public void onThermalChanged(String ifname, int thermal_state) throws RemoteException {
            mHandler.post(() -> {
                mCallback.onThermalChanged(ifname, thermal_state);
            });
        }
    }

    public void registerVendorEventCallback(VendorEventCallback callback, Handler handler) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "registerVendorEventCallback: callback=" + callback + ", handler=" + handler);

        Looper looper = (handler == null) ? mContext.getMainLooper() : handler.getLooper();
        try {
            mService.registerVendorEventCallback(new VendorEventCallbackProxy(looper, callback),
                    callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void unregisterVendorEventCallback(VendorEventCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "unregisterVendorEventCallback: callback=" + callback);

        try {
            mService.unregisterVendorEventCallback(callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public ThermalData getThermalInfo(String ifname) {
        if (ifname == null) throw new IllegalArgumentException("ifname cannot be null");
        Log.v(TAG, "getThermalInfo: ifname=" + ifname);
        try {
            return mService.getThermalInfo(ifname);
        } catch (RemoteException e) {
            Log.e(TAG, "getThermalInfo: " + e);
            return null;
        }
    }

    /**
     * Set TX power limitation in dBm.
     *
     * @param ifname Name of the interface.
     * @param dbm TX power in dBm.
     * @return Results of setTxPower.
     *
     * @throws IllegalArgumentException if ifname is null.
     */
    public boolean setTxPower(String ifname, int dbm) {
        try {
            return mService.setTxPower(ifname, dbm);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * Base class for vendor event callback. Should be extended by applications and
     * set when calling
     * {@link QtiWifiManager#registerVendorEventCallback(VendorCallback, Handler)}.
     *
     */
    public interface VendorEventCallback {
        public abstract void onThermalChanged(String ifname, int thermal_state);
    }

    /**
     * Callback proxy for CsiCallback objects.
     * Base class for Qti interface callbacks . Should be extended by applications and set when calling
     * {@link QtiWifiManager#registerCallback(QtiInterfaceCallback, Handler)}.
     *
     */
    public interface QtiInterfaceCallback {

        /**
         * Used to indicate the disconnection from the currently connected
         * network on this interface.
         *
         * @param bssid BSSID of the AP from which we disconnected.
         * @param locallyGenerated If the disconnect was triggered by
         *        wpa_supplicant.
         * @param reasonCode 802.11 code to indicate the disconnect reason
         *        from access point. Refer to section 8.4.1.7 of IEEE802.11 spec.
         *
         */
        public abstract void onNetworkDisconnect(byte[] bssid, int reasonCode, boolean locallyGenerated);

        /**
         * Callback indicating that the chip has encountered a fatal error.
         * These alerts notify the clients about any fatal error events
         * that the chip encounters.
         *
         * @param errorCode Vendor defined error code.
         * @param buffer Vendor defined data used for debugging.
         *
         */
        public abstract void onWifiAlert(int errorCode, byte[] buffer);
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

    /**
     * Callback proxy for QtiInterfaceCallback objects.
     *
     */
    private static class QtiInterfaceCallbackProxy extends IQtiInterfaceCallback.Stub {
        private final Handler mHandler;
        private final QtiInterfaceCallback mCallback;

        QtiInterfaceCallbackProxy(Looper looper, QtiInterfaceCallback callback) {
            mHandler = new Handler(looper);
            mCallback = callback;
        }

        @Override
        public void onNetworkDisconnect(byte[] bssid, int reasonCode, boolean locallyGenerated) throws RemoteException {
            mHandler.post(() -> {
                mCallback.onNetworkDisconnect(bssid, reasonCode, locallyGenerated);
            });
       }

        @Override
        public void onWifiAlert(int errorCode, byte [] buffer) throws RemoteException {
            mHandler.post(() -> {
                mCallback.onWifiAlert(errorCode, buffer);
            });

        }
    }

    /**
     * Registers a callback for csi events.
     * Caller can unregister a previously registered callback using
     * {@link #unregisterCsiCallback(CsiCallback)}
     *
     * @param callback CsiCallback for the application to receive updates about
     * csi events.
     * @param handler Handler to be used for callbacks. If the caller passes a null Handler,
     * the main thread will be used.
     *
     */
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
     * @param callback Callback to unregister for csi events.
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

    /**
     * API to register for CSI callbacks and start CSI data collection.
     * CsiCallback is used by caller to receive updates about CSI events.
     * Optional Handler to be used for callbacks.
     * If the caller passes a null Handler, the main thread will be used.
     *
     * <p>
     * Applications should have com.qualcomm.permission.QTI_WIFI permission.
     * Callers without the permission would trigger a {@link java.lang.SecurityException}
     * <p>
     *
     * @param callback CsiCallback for the application to receive csi event updates.
     * @param handler Handler to be used for callbacks.
     *
     */
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

    /**
     * API to stop CSI data collection and unregister for CsiCallback.
     *
     * <p>
     * Applications should have com.qualcomm.permission.QTI_WIFI permission.
     * Callers without the permission would trigger a {@link java.lang.SecurityException}
     * <p>
     *
     * @param callback Callback to unregister for csi events.
     *
     */
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

    /**
     * Get BSS information.
     *
     * Gets the BSS information. The information obtained through these
     * commands signify the current info in connected state and
     * latest cached information during the connected state , if queried
     * when in disconnected state.
     *
     * <p>
     * Applications should have com.qualcomm.permission.QTI_WIFI permission.
     * Callers without the permission would trigger a {@link java.lang.SecurityException}
     * <p>
     *
     * @return String containing the information about BSS.
     * Output Example: 00:c1:64 5785 20 0 130 4 1 0 0 96 -96 9 1 0 0
     *
     */
    public String getBssInfo() {
        try {
            return mService.getBssInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get BSS Stats information.
     *
     * <p>
     * Applications should have com.qualcomm.permission.QTI_WIFI permission.
     * Callers without the permission would trigger a {@link java.lang.SecurityException}
     * <p>
     *
     * @param MacAddress mac address of the BSS
     * @return String containing the detailed information about BSS.
     * Output Example: 00c164 Hydra 5785 20 0 130 4 -1 -1 -1 96 -96 9 0 -1 -1 -1 -1 0 US 0 -1 -1 -1 0 0 0 0 0 0 0 0 0
     *
     */
    public String getStatsBssInfo(byte[] MacAddress) {
        try {
            return mService.getStatsBssInfo(MacAddress);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a callback for QtiInterface.
     * Caller can unregister a previously registered callback using
     * {@link #unregisterCallback(QtiInterfaceCallback)}
     *
     * <p>
     * Applications should have com.qualcomm.permission.QTI_WIFI. Callers without
     * the permission would trigger a {@link java.lang.SecurityException}
     * <p>
     *
     * @param callback QtiInterfaceCallback for the application to receive updates about
     * Network status and Wifi alerts.
     * @param handler Handler to be used for callbacks. If the caller passes a null Handler,
     * the main thread will be used
     *
     */
    public void registerCallback(QtiInterfaceCallback callback, Handler handler) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "registerCallback: callback=" + callback + ", handler=" + handler);

        Looper looper = (handler == null) ? mContext.getMainLooper() : handler.getLooper();
        Binder binder = new Binder();
        try {
            mService.registerCallback(binder, new QtiInterfaceCallbackProxy(looper, callback),
                    callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allow callers to unregister a previously registered callback. After calling this method,
     * applications will no longer receive Qti interface events.
     *
     * <p>
     * Applications should have com.qualcomm.permission.QTI_WIFI. Callers without
     * the permission would trigger a {@link java.lang.SecurityException}
     * <p>
     *
     * @param callback Callback to unregister for Qti interface events
     *
     */
    public void unregisterCallback(QtiInterfaceCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        Log.v(TAG, "unregisterCallback: callback=" + callback);

        try {
            mService.unregisterCallback(callback.hashCode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}
