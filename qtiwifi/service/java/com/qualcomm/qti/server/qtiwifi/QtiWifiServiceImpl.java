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

package com.qualcomm.qti.server.qtiwifi;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;
import android.net.wifi.WifiManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;

import com.qualcomm.qti.qtiwifi.ICsiCallback;
import com.qualcomm.qti.qtiwifi.IQtiWifiManager;

public final class QtiWifiServiceImpl extends IQtiWifiManager.Stub {
    private static final String TAG = "QtiWifiServiceImpl";
    private static final boolean DBG = true;
    private boolean mServiceStarted = false;
    private boolean mInitializeHals = false;
    private WifiManager mWifiManager;

    private final Context mContext;
    private Context mServiceContext = null;
    private Object mLock = new Object();
    private final IntentFilter mQtiIntentFilter;

    QtiWifiCsiHal qtiWifiCsiHal;
    QtiSupplicantStaIfaceHal qtiSupplicantStaIfaceHal;

    public QtiWifiServiceImpl(Context context) {
        Log.d(TAG, "QtiWifiServiceImpl ctor");
        mContext = context;
        if (mServiceStarted == false) {
            mServiceContext = context;
            Intent serviceIntent = new Intent(context, QtiWifiService.class);
            context.startForegroundService(serviceIntent);
            Log.d(TAG, "QtiWifiService has started");
            mServiceStarted = true;
        }
        mQtiIntentFilter = new IntentFilter("android.net.wifi.supplicant.STATE_CHANGE");
        mQtiIntentFilter.addAction("android.net.wifi.WIFI_STATE_CHANGED");
        mContext.registerReceiver(mQtiReceiver, mQtiIntentFilter);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (mWifiManager.isWifiEnabled()) {
            Log.d(TAG, "isWifiEnabled true");
            mInitializeHals = true;
            checkAndInitCfrHal();
            checkAndInitSupplicantStaIfaceHal();
        }
    }

    protected void destroyService() {
        Log.d(TAG, "destroyService()");
        mServiceStarted = false;
    }

    public void checkAndInitCfrHal() {
        Log.i(TAG, "checkAndInitCfrHal");
        qtiWifiCsiHal = new QtiWifiCsiHal();
        qtiWifiCsiHal.initialize();
    }

    public void checkAndInitSupplicantStaIfaceHal() {
        Log.i(TAG, "checkAndInitSupplicantStaIfaceHal");
        qtiSupplicantStaIfaceHal = new QtiSupplicantStaIfaceHal();
        qtiSupplicantStaIfaceHal.initialize();
        if (!qtiSupplicantStaIfaceHal.setupVendorIface("wlan0")) {
            Log.e(TAG, "Failed to setup iface in supplicant on wlan0");
        }
    }

    private final BroadcastReceiver mQtiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                 int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                 if ((state == WifiManager.WIFI_STATE_ENABLED) && !mInitializeHals) {
                     Log.i(TAG, "Didn't iniltailze the hals, now initializing");
                     checkAndInitCfrHal();
                     checkAndInitSupplicantStaIfaceHal();
                 } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                     mInitializeHals = false;
                 }
            }
        }
    };

    @Override
    public void registerCsiCallback(IBinder binder, ICsiCallback callback,
            int callbackIdentifier) {
        // verify arguments
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        enforceAccessPermission();
        if (DBG) {
            Log.i(TAG, "registerCsiCallback uid=%" + Binder.getCallingUid());
        }
        qtiWifiCsiHal.registerCsiCallback(binder, callback, callbackIdentifier);
    }

    @Override
    public void unregisterCsiCallback(int callbackIdentifier) {
        enforceAccessPermission();
        if (DBG) {
            Log.i(TAG, "unregisterCsiCallback uid=%" + Binder.getCallingUid());
        }
        qtiWifiCsiHal.unregisterCsiCallback(callbackIdentifier);
    }

    /**
     * see {@link com.qualcomm.qti.qtiwifi.QtiWifiManager#startCsi}
     */
    public void startCsi() {
        enforceChangePermission();
        Log.i(TAG, "startCsi");
        qtiWifiCsiHal.startCsi();
        qtiSupplicantStaIfaceHal.doDriverCmd("CSI start 0");
    }

    /**
     * see {@link com.qualcomm.qti.qtiwifi.QtiWifiManager#stopCsi}
     */
    public void stopCsi() {
        enforceChangePermission();
        Log.i(TAG, "stopCsi");
        qtiSupplicantStaIfaceHal.doDriverCmd("CSI stop");
        qtiWifiCsiHal.stopCsi();
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                "QtiWifiServiceImpl");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "QtiWifiServiceImpl");
    }
}
