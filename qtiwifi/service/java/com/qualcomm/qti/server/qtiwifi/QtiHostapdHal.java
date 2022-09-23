/*
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 *
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.qualcomm.qti.server.qtiwifi;

import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.HwRemoteBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.qualcomm.qti.server.qtiwifi.QtiWifiServiceImpl.WifiHalListener;

public class QtiHostapdHal {
    private static final String TAG = "QtiHostapdHal";

    private final Object mLock = new Object();
    private boolean mVerboseLoggingEnabled = true;

    // Vendor HAL interface object - might be implemented by HIDL or AIDL
    private IQtiHostapdHal mQtiHostapdHal;

    public QtiHostapdHal() {
        Log.w(TAG, "constructor of QtiHostapdHal called");
        mQtiHostapdHal = createVendorHostapdHalMockable();
        if (mQtiHostapdHal == null) {
            Log.w(TAG, "Failed to get internal ISupplicantVendorStaIfaceHal instance.");
        }
    }

    /**
     * Initialize the STA Iface HAL. Creates the internal IQtiHostapdHal
     * object and calls its initialize method.
     *
     * @return true if the initialization succeeded
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mQtiHostapdHal == null) {
                Log.w(TAG, "Internal ISupplicantVendorStaIfaceHal instance does not exist.");
                return false;
            }
            if (!mQtiHostapdHal.initialize()) {
                Log.e(TAG, "Failed to init ISupplicantVendorStaIfaceHal, stopping startup.");
                return false;
            }
            return true;
        }
    }

    /**
     * Wrapper function to create the IHostapdHal object.
     * Created to be mockable in unit tests.
     */
    protected IQtiHostapdHal createVendorHostapdHalMockable() {
        synchronized (mLock) {
            if (QtiHostapdHalAidlImpl.serviceDeclared()) {
                Log.i(TAG, "Initializing QtiHostapdHal using AIDL implementation.");
                return new QtiHostapdHalAidlImpl();
            } else if (QtiHostapdHalHidlImpl.serviceDeclared()) {
                Log.i(TAG, "Initializing QtiHostapdHal using HIDL implementation.");
                return new QtiHostapdHalHidlImpl();
            }
            Log.e(TAG, "No HIDL or AIDL service available for HostapdHal.");
            return null;
        }
    }

    /**
     * run Driver command
     *
     * @param ifaceName Interface Name
     * @param command Driver Command
     */
    public String doDriverCmd(String iface, String command)
    {
        synchronized (mLock) {
            final String methodStr = "doDriverCmd";
            if (mQtiHostapdHal == null) {
                return "QtiStaIfaceHal is null";
            }
            return mQtiHostapdHal.doDriverCmd(iface, command);
        }
    }

    /**
     * List available SAP interfaces
     *
     * @return active SAP instances
     */
    public String[] listVendorInterfaces() {
        synchronized (mLock) {
            if (mQtiHostapdHal == null) {
                Log.e(TAG, "call listVendorInterfaces but mQtiHostapdHal is null??");
                return null;
            }
            return mQtiHostapdHal.listVendorInterfaces();
        }
    }

    /**
     * Register Hal listener for vendor events
     */
    public void registerWifiHalListener(WifiHalListener listener) {
        synchronized (mLock) {
            mQtiHostapdHal.registerWifiHalListener(listener);
        }
    }
}
