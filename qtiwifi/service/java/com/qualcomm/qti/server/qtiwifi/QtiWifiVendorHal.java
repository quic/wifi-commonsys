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
 *
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiChipEventCallback;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.hardware.wifi.V1_0.WifiDebugRingBufferStatus;
import android.hidl.manager.V1_0.IServiceNotification;
import android.hidl.manager.V1_2.IServiceManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHwBinder.DeathRecipient;
import android.os.RemoteException;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

import com.qualcomm.qti.server.qtiwifi.util.GeneralUtil.Mutable;

public class QtiWifiVendorHal {
    private static final String TAG = "QtiWifiVendorHal";

    private final Object mLock = new Object();
    private WifiDeathRecipient mIWifiDeathRecipient;
    private ServiceManagerDeathRecipient mServiceManagerDeathRecipient;

    private IServiceManager mServiceManager;
    private IWifi mWifi;
    private IWifiChip mIWifiChip;
    private QtiWifiHandler mQtiWifiHandler;

    public QtiWifiVendorHal(QtiWifiHandler qtiWifiHandler) {
        mIWifiDeathRecipient = new WifiDeathRecipient();
        mServiceManagerDeathRecipient = new ServiceManagerDeathRecipient();
        mQtiWifiHandler = qtiWifiHandler;

    }

    private class ServiceManagerDeathRecipient implements DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
                Log.e(TAG, "IServiceManager died: cookie=" + cookie);
                synchronized (mLock) {
                    mServiceManager = null;
                }
        }
    }

    private final IServiceNotification mServiceNotificationCallback =
            new IServiceNotification.Stub() {
                @Override
                public void onRegistration(String fqName, String name,
                                           boolean preexisting) {
                    Log.d(TAG, "IWifi registration notification: fqName=" + fqName
                            + ", name=" + name + ", preexisting=" + preexisting);
                    synchronized (mLock) {
                        initIWifiIfNecessary();
                    }
                }
            };

    /**
     * Wrapper function to access the HIDL services. Created to be mockable in unit-tests.
     */
    protected IWifi getWifiServiceMockable() {
        try {
            return IWifi.getService(true /* retry */);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception getting IWifi service: " + e);
            return null;
        }
    }

    protected IServiceManager getServiceManagerMockable() {
        try {
            return IServiceManager.getService();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception getting IServiceManager: " + e);
            return null;
        }
    }

    /**
     * Failures of IServiceManager are most likely system breaking in any case. Behavior here
     * will be to WTF and continue.
     */
    private void initIServiceManagerIfNecessary() {
        Log.d(TAG, "initIServiceManagerIfNecessary");

        synchronized (mLock) {
            if (mServiceManager != null) {
                return;
            }

            mServiceManager = getServiceManagerMockable();
            if (mServiceManager == null) {
                Log.wtf(TAG, "Failed to get IServiceManager instance");
            } else {
                try {
                    if (!mServiceManager.linkToDeath(
                            mServiceManagerDeathRecipient, /* don't care */ 0)) {
                        Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                        mServiceManager = null;
                        return;
                    }

                    if (!mServiceManager.registerForNotifications(IWifi.kInterfaceName, "",
                            mServiceNotificationCallback)) {
                        Log.wtf(TAG, "Failed to register a listener for IWifi service");
                        mServiceManager = null;
                    }
                } catch (RemoteException e) {
                    Log.wtf(TAG, "Exception while operating on IServiceManager: " + e);
                    mServiceManager = null;
                }
            }
        }
    }

    private class WifiDeathRecipient implements DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
                Log.e(TAG, "IWifi HAL service died! Have a listener for it ... cookie=" + cookie);
                synchronized (mLock) {
                    mWifi = null;
                }
        }
    }

    /**
     * Registers event listeners on all IWifiChips after a successful start!
     *
     * We don't need the listeners since any callbacks are just confirmation of status codes we
     * obtain directly from mode changes or interface creation/deletion.
     *
     * Relies (to the degree we care) on the service removing all listeners when Wi-Fi is stopped.
     */
    private void initIWifiChipListeners() {
        Log.d(TAG, "initIWifiChipListeners");

        synchronized (mLock) {
            try {
                Mutable<Boolean> statusOk = new Mutable<>(false);
                Mutable<ArrayList<Integer>> chipIdsResp = new Mutable<>();

                // get all chip IDs
                mWifi.getChipIds((WifiStatus status, ArrayList<Integer> chipIds) -> {
                    statusOk.value = false;
                    if (status.code == WifiStatusCode.SUCCESS) {
                        if (chipIds == null) {
                            Log.wtf(TAG, "getChipIds failed, chipIds is null");
                            return;
                        }
                        statusOk.value = true;
                    }
                    if (statusOk.value) {
                        chipIdsResp.value = chipIds;
                    } else {
                        Log.e(TAG, "getChipIds failed: " + statusString(status));
                    }
                });
                if (!statusOk.value) {
                    return;
                }

                Log.d(TAG, "getChipIds=" + chipIdsResp.value);
                if (chipIdsResp.value.size() == 0) {
                    Log.e(TAG, "Should have at least 1 chip!");
                    return;
                }

                // register a callback for each chip
                Mutable<IWifiChip> chipResp = new Mutable<>();
                for (Integer chipId: chipIdsResp.value) {
                    mWifi.getChip(chipId, (WifiStatus status, IWifiChip chip) -> {
                        statusOk.value = false;
                        if (status.code == WifiStatusCode.SUCCESS) {
                            if (chip == null) {
                                Log.wtf(TAG, "getChip failed, chip " + chipId + " is null");
                            }
                            statusOk.value = true;
                        }
                        if (statusOk.value) {
                            chipResp.value = chip;
                        } else {
                            Log.e(TAG, "getChip failed: " + statusString(status));
                        }
                    });
                    if (!statusOk.value) {
                        continue;
                    }

                    android.hardware.wifi.V1_4.IWifiChipEventCallback.Stub callbackV1_4 =
                            new android.hardware.wifi.V1_4.IWifiChipEventCallback.Stub() {
                                @Override
                                public void onChipReconfigured(int modeId) {}

                                @Override
                                public void onChipReconfigureFailure(WifiStatus status) {}

                                @Override
                                public void onIfaceAdded(int type, String name) {
                                    Log.d(TAG, "onIfaceAdded: type=" + type + ", name=" + name);
                                }

                                @Override
                                public void onIfaceRemoved(int type, String name) {
                                    Log.d(TAG, "onIfaceRemoved: type=" + type + ", name=" + name);
                                }

                                @Override
                                public void onDebugRingBufferDataAvailable(
                                        WifiDebugRingBufferStatus status,
                                        ArrayList<Byte> data) {}

                                @Override
                                public void onDebugErrorAlert(int errorCode,
                                        ArrayList<Byte> debugData)
                                        throws RemoteException {
                                    // TODO: Lets schedule this to mEventhandler
                                    callonDebugAlert(errorCode, debugData);
                                }

                                @Override
                                public void onRadioModeChange(
                                        ArrayList<android.hardware.wifi.V1_2.IWifiChipEventCallback.RadioModeInfo>
                                        radioModeInfoList) {}

                                @Override
                                public void onRadioModeChange_1_4(ArrayList<RadioModeInfo> radioModeInfoList) {}
                            };
                    android.hardware.wifi.V1_4.IWifiChip chip14 =
                            android.hardware.wifi.V1_4.IWifiChip.castFrom(chipResp.value);
                    if (chip14 == null) {
                        Log.e(TAG, "V1_4.IWifiChip is null");
                        return;
                    }
                    WifiStatus status = chip14.registerEventCallback_1_4(callbackV1_4);
                    if (status.code != WifiStatusCode.SUCCESS) {
                        Log.e(TAG, "registerEventCallback_1_4 failed: " + statusString(status));
                        continue;
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "initIWifiChipListeners: exception: " + e);
                return;
            }
        }
    }

    private static String statusString(WifiStatus status) {
        if (status == null) {
            return "status=null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(status.code).append(" (").append(status.description).append(")");
        return sb.toString();
    }

    private void callonDebugAlert(int errorCode, ArrayList<Byte> debugData) {
        Log.d(TAG, "onDebugErrorAlert " + errorCode);
        synchronized (mLock) {
            if (debugData == null) return;
        }
        mQtiWifiHandler.notifyClientsOnWifiAlert(
                errorCode, byteArrayFromArrayList(debugData));
    }

    /**
     * Convert from an array list of Byte to an array of primitive bytes.
     */
    private static byte[] byteArrayFromArrayList(ArrayList<Byte> bytes) {
        byte[] byteArray = new byte[bytes.size()];
        int i = 0;
        for (Byte b : bytes) {
            byteArray[i++] = b;
        }
        return byteArray;
    }

    /**
     * Initialize IWifi and register death listener and event callback.
     *
     * - It is possible that IWifi is not ready - we have a listener on IServiceManager for it.
     * - It is not expected that any of the registrations will fail. Possible indication that
     *   service died after we obtained a handle to it.
     *
     * Here and elsewhere we assume that death listener will do the right thing!
    */
    private void initIWifiIfNecessary() {
        Log.d(TAG, "initIWifiIfNecessary");

        synchronized (mLock) {
            if (mWifi != null) {
                return;
            }

            try {
                mWifi = getWifiServiceMockable();
                if (mWifi == null) {
                    Log.e(TAG, "IWifi not (yet) available - but have a listener for it ...");
                    return;
                }

                if (!mWifi.linkToDeath(mIWifiDeathRecipient, /* don't care */ 0)) {
                    Log.e(TAG, "Error on linkToDeath on IWifi - will retry later");
                    mWifi = null;
                    return;
                }
                initIWifiChipListeners();

            } catch (RemoteException e) {
                mWifi = null;
                Log.e(TAG, "Exception while operating on IWifi: " + e);
            }
        }
    }

    /**
     * Initialize the STA Iface HAL. Creates the internal IQtiSupplicantStaIfaceHal
     * object and calls its initialize method.
     *
     * @return true if the initialization succeeded
     */
    public void initialize() {
        initIServiceManagerIfNecessary();
        if (mWifi != null) {
            initIWifiChipListeners();
        }
    }

}
