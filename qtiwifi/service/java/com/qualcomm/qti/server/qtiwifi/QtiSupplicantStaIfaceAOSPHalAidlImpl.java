/*
 * Copyright (C) 2021 The Android Open Source Project
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

 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import android.annotation.NonNull;
import android.hardware.wifi.supplicant.AnqpData;
import android.hardware.wifi.supplicant.Hs20AnqpData;
import android.hardware.wifi.supplicant.AssociationRejectionData;
import android.hardware.wifi.supplicant.DppConnectionKeys;
import android.hardware.wifi.supplicant.BssTmData;
import android.hardware.wifi.supplicant.ISupplicant;
import android.hardware.wifi.supplicant.ISupplicantStaIface;
import android.hardware.wifi.supplicant.ISupplicantStaIfaceCallback;
import android.hardware.wifi.supplicant.IfaceInfo;
import android.hardware.wifi.supplicant.IfaceType;
import android.hardware.wifi.supplicant.QosPolicyData;
import com.qualcomm.qti.server.qtiwifi.util.GeneralUtil.Mutable;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * HAL calls to set up/tear down the supplicant daemon and make requests
 * related to station mode. Uses the AIDL supplicant interface.
 * To maintain thread-safety, the locking protocol is that every non-static method (regardless of
 * access level) acquires mLock.
 */
public class QtiSupplicantStaIfaceAOSPHalAidlImpl implements IQtiSupplicantStaIfaceAOSPHal {
    private static final String TAG = "QtiSupplicantStaIfaceAOSPHalAidlImpl";
    private static final String HAL_INSTANCE_NAME = ISupplicant.DESCRIPTOR + "/default";

    private final Object mLock = new Object();

    // Supplicant HAL interface objects
    private ISupplicant mISupplicant = null;
    private Map<String, ISupplicantStaIface> mISupplicantStaIfaces = new HashMap<>();
    private SupplicantDeathRecipient mSupplicantDeathRecipient;
    private QtiSupplicantIface mQtiSupplicantIface;
    private QtiWifiHandler mQtiWifiHandler;
    private class SupplicantDeathRecipient implements DeathRecipient {
        @Override
        public void binderDied() {
            synchronized (mLock) {
                Log.w(TAG, "ISupplicant binder died.");
                supplicantServiceDiedHandler();
            }
        }
    }

    public QtiSupplicantStaIfaceAOSPHalAidlImpl(QtiSupplicantIface qtiSupplicantIface, QtiWifiHandler qtiWifiHandler) {
        Log.i(TAG, "QtiSupplicantStaIfaceAOSPHalAidlImpl() invoked");
        mQtiSupplicantIface = qtiSupplicantIface;
        mQtiWifiHandler = qtiWifiHandler;
        mSupplicantDeathRecipient = new SupplicantDeathRecipient();
    }

    /**
     * Checks whether the ISupplicant service is declared, and therefore should be available.
     *
     * @return true if the ISupplicant service is declared
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mISupplicant != null) {
                Log.i(TAG, "Service is already initialized, skipping initialize method");
                return true;
            }
            mISupplicantStaIfaces.clear();
            getSupplicantInstance();
            return serviceDeclared();
        }
    }

    /**
     * Helper method to look up the specified iface.
     */
    private ISupplicantStaIface fetchStaIface() {
        synchronized (mLock) {
            if (mQtiSupplicantIface.getIfaceName() != null) {
                try {
                    return mISupplicant.getStaInterface(mQtiSupplicantIface.getIfaceName());
                } catch (RemoteException e) {
                    Log.e(TAG, "ISupplicant.getStaInterface exception: " + e);
                    supplicantServiceDiedHandler();
                    return null;
                }
            } else {
                /** List all supplicant STA Ifaces */
                IfaceInfo[] supplicantStaIfaces = new IfaceInfo[10];
                try {
                    final String methodStr = "listInterfaces";
                    if (!checkSupplicantAndLogFailure(methodStr)) return null;
                    supplicantStaIfaces = mISupplicant.listInterfaces();
                } catch (RemoteException e) {
                    Log.e(TAG, "ISupplicant.listInterfaces exception: " + e);
                    supplicantServiceDiedHandler();
                    return null;
                }
                if (supplicantStaIfaces.length == 0) {
                    Log.e(TAG, "Got zero HIDL supplicant Sta ifaces. Stopping supplicant AIDL startup.");
                    return null;
                }
                Mutable<ISupplicantStaIface> supplicantStaIface = new Mutable<>();
                for (IfaceInfo ifaceInfo : supplicantStaIfaces) {
                    if (ifaceInfo.type == IfaceType.STA) {
                        try {
                            final String methodStr = "getStaInterface";
                            if (!checkSupplicantAndLogFailure(methodStr)) return null;
                            mQtiSupplicantIface.setIfaceName(ifaceInfo.name);
                            supplicantStaIface.value = mISupplicant.getStaInterface(ifaceInfo.name);
                        } catch (RemoteException e) {
                            Log.e(TAG, "ISupplicantVendor.getInterface exception: " + e);
                            supplicantServiceDiedHandler();
                            return null;
                        }
                        break;
                    }
                }
                return supplicantStaIface.value;
            }
        }
    }

    private boolean registerCallback(
            ISupplicantStaIface iface, ISupplicantStaIfaceCallback callback) {
        synchronized (mLock) {
            String methodStr = "registerCallback";
            if (iface == null) {
                return false;
            }
            try {
                iface.registerCallback(callback);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * Setup a STA interface for the specified iface name.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    public boolean setupIface(@NonNull String ifaceName) {
        synchronized (mLock) {

            ISupplicantStaIface iface = fetchStaIface();
            if (iface == null) {
                Log.e(TAG, "failed to get iface");
                return false;
            }

            ISupplicantStaIfaceCallback callback = new ISupplicantStaIfaceCallback.Stub() {
                @Override
                public void onNetworkAdded(int id) {
                }

                @Override
                public void onNetworkRemoved(int id) {
                }

                @Override
                public void onStateChanged(int newState, byte[/* 6 */] bssid, int id,
                       byte[] ssid, boolean filsHlpSent) {
                }

                @Override
                public void onAnqpQueryDone(byte[/* 6 */] bssid, AnqpData data, Hs20AnqpData hs20Data) {}
                @Override
                public void onHs20IconQueryDone(byte[/* 6 */] bssid, String fileName, byte[] data) {}

                @Override
                public void onHs20SubscriptionRemediation(byte[/* 6 */] bssid, byte osuMethod, String url) {}

                @Override
                public void onHs20DeauthImminentNotice(byte[/* 6 */] bssid, int reasonCode,
                       int reAuthDelayInSec, String url) {}

                @Override
                public void onDisconnected(byte[/* 6 */] bssid, boolean locallyGenerated, int reasonCode) {
                    Log.d(TAG, "onDisconnected " + "bssid " + bssid +
                        " locallyGenerated " + locallyGenerated + " reason code " + reasonCode);
                    mQtiWifiHandler.notifyClientsOnNetworkDisconnect(bssid, reasonCode, locallyGenerated);
                }

                @Override
                public void onAssociationRejected(AssociationRejectionData assocRejectData) {}

                @Override
                public void onAuthenticationTimeout(byte[/* 6 */] bssid) {}

                @Override
                public void onBssidChanged(byte reason, byte[/* 6 */] bssid) {}

                @Override
                public void onEapFailure(byte[/* 6 */] bssid, int errorCode) {}

                @Override
                public void onWpsEventSuccess() {}

                @Override
                public void onWpsEventFail(byte[/* 6 */] bssid, int configError, int errorInd) {}

                @Override
                public void onWpsEventPbcOverlap() {}

                @Override
                public void onExtRadioWorkStart(int id) {}

                @Override
                public void onExtRadioWorkTimeout(int id) {}

                @Override
                public void onDppSuccessConfigReceived(byte[] ssid, String password,
                       byte[] psk, int securityAkm, DppConnectionKeys keys) {}

                @Override
                public void onDppSuccessConfigSent() {}

                @Override
                public void onDppProgress(int code) {}

                @Override
                public void onDppFailure(int code, String ssid, String channelList, char[] bandList) {}

                @Override
                public void onPmkCacheAdded(long expirationTimeInSec, byte[] serializedEntry) {}

                @Override
                public void onDppSuccess(int code) {}

                @Override
                public void onBssTmHandlingDone(BssTmData tmData) {}

                @Override
                public void onHs20TermsAndConditionsAcceptanceRequestedNotification(byte[/* 6 */] bssid,
                       String url) {}

                @Override
                public void onNetworkNotFound(byte[] ssid) {}

                @Override
                public void onQosPolicyReset() {}

                @Override
                public void onQosPolicyRequest(int qosPolicyRequestId, QosPolicyData[] qosPolicyData) {}

                @Override
                public void onAuxiliarySupplicantEvent(int eventCode, byte[] bssid,
                       String reasonString) {}

                @Override
                public String getInterfaceHash() {
                    return null;
                }

                @Override
                public int getInterfaceVersion() {
                    return -1;
                }
            };
            if (registerCallback(iface, callback)) {
                mISupplicantStaIfaces.put(ifaceName, iface);
                return true;
            } else {
                Log.e(TAG, "Unable to register callback for iface " + ifaceName);
                return false;
            }
        }
    }

    /**
     * Indicates whether the AIDL service is declared
     */
    public static boolean serviceDeclared() {
        return ServiceManager.isDeclared(HAL_INSTANCE_NAME);
    }

    private void supplicantServiceDiedHandler() {
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantStaIfaces.clear();
        }
    }

    /**
     * Start the supplicant daemon.
     *
     * @return true on success, false otherwise.
     */
    public void getSupplicantInstance() {
        final String methodStr = "getSupplicantInstance";
        if (mISupplicant != null) {
            Log.i(TAG, "Service is already initialized, skipping " + methodStr);
            return;
        }

        mISupplicant = getSupplicantMockable();
        if (mISupplicant == null) {
            Log.e(TAG, "Unable to obtain ISupplicant binder.");
            return;
        }
        Log.i(TAG, "Obtained ISupplicant binder.");

        try {
            IBinder serviceBinder = getServiceBinderMockable();
            if (serviceBinder == null) {
                return;
            }
            serviceBinder.linkToDeath(mSupplicantDeathRecipient, /* flags= */  0);
            return;
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
            return;
        }
    }

    /**
     * Returns false if SupplicantVendor is null, and logs failure to call methodStr
     */
    private boolean checkSupplicantAndLogFailure(final String methodStr) {
        synchronized (mLock) {
            if (mISupplicant == null) {
                Log.e(TAG, "Can't call " + methodStr + ", ISupplicant is null");
                return false;
            }
            return true;
        }
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantStaIfaces.clear();
            Log.e(TAG,
                    "ISupplicantStaIface." + methodStr + " failed with remote exception: ", e);
        }
    }

    private void handleServiceSpecificException(ServiceSpecificException e, String methodStr) {
        synchronized (mLock) {
            Log.e(TAG, "ISupplicantStaIface." + methodStr + " failed with "
                    + "service specific exception: ", e);
        }
    }

    /**
     * Wrapper functions to access HAL objects, created to be mockable in unit tests
     */
    protected ISupplicant getSupplicantMockable() {
        synchronized (mLock) {
            try {
                return ISupplicant.Stub.asInterface(
                        ServiceManager.waitForDeclaredService(HAL_INSTANCE_NAME));
            } catch (Exception e) {
                Log.e(TAG, "Unable to get ISupplicant service, " + e);
                return null;
            }
        }
    }

    protected IBinder getServiceBinderMockable() {
        synchronized (mLock) {
            if (mISupplicant == null) {
                return null;
            }
            return mISupplicant.asBinder();
        }
    }
}
