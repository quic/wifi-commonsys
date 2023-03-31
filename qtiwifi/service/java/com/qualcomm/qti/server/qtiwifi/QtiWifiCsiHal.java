/*
 * Copyright (C) 2017 The Android Open Source Project
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
 *
 * Changes from Qualcomm Innovation Center are provided under the following license:
 * Copyright (c) 2021-2023 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import android.os.IBinder;
import android.util.Log;

import com.qualcomm.qti.qtiwifi.ICsiCallback;

public class QtiWifiCsiHal {
    private static final String TAG = "QtiWifiCsiHal";

    private final Object mLock = new Object();

    private IQtiWifiCsiHal mQtiWifiCsiHal;

    public QtiWifiCsiHal() {
        mQtiWifiCsiHal = createWifiCsiHalMockable();
        if (mQtiWifiCsiHal == null) {
            Log.e(TAG, "Failed to get internal IQtiWifiCsiHal instance.");
        }
    }

    /**
     * Registers a service notification for the IWifiCfr service, which triggers intialization of
     * the IWifiCfr
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mQtiWifiCsiHal == null) {
                Log.e(TAG, "Internal IQtiWifiCsiHal instance does not exist.");
                return false;
            }
            if (!mQtiWifiCsiHal.initialize()) {
                Log.e(TAG, "Failed to init IQtiWifiCsiHal, stopping startup.");
                return false;
            }
            return true;
        }
    }

    private IQtiWifiCsiHal createWifiCsiHalMockable() {
        synchronized (mLock) {
            if (QtiWifiCsiHalHidlImpl.serviceDeclared()) {
                Log.i(TAG, "Initializing QtiWifiCsiHalHidlImpl using HIDL implementation.");
                return new QtiWifiCsiHalHidlImpl();
            } else if (QtiWifiCsiHalAidlImpl.serviceDeclared()) {
                Log.i(TAG, "Initializing QtiWifiCsiHalHidlImpl using AIDL implementation.");
                return new QtiWifiCsiHalAidlImpl();
            }
            Log.e(TAG, "No HIDL or AIDL service available for QtiWifiCsiHal.");
            return null;
        }
    }

    public void registerCsiCallback(IBinder binder, ICsiCallback callback,
            int callbackIdentifier) {
        if (mQtiWifiCsiHal == null) {
            Log.e(TAG, "Internal IQtiWifiCsiHal instance does not exist.");
        }
        mQtiWifiCsiHal.registerCsiCallback(binder, callback, callbackIdentifier);
    }

    public void unregisterCsiCallback(int callbackIdentifier) {
        if (mQtiWifiCsiHal == null) {
            Log.e(TAG, "Internal IQtiWifiCsiHal instance does not exist.");
        }
        mQtiWifiCsiHal.unregisterCsiCallback(callbackIdentifier);
    }

    public void startCsi() {
        if (mQtiWifiCsiHal == null) {
            Log.e(TAG, "Internal IQtiWifiCsiHal instance does not exist.");
        }
        mQtiWifiCsiHal.startCsi();
    }

    public void stopCsi() {
        if (mQtiWifiCsiHal == null) {
            Log.e(TAG, "Internal IQtiWifiCsiHal instance does not exist.");
        }
        mQtiWifiCsiHal.stopCsi();
    }

}
