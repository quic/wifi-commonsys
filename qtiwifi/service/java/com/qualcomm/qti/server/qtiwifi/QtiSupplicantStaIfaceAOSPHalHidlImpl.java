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
import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantIface;
import android.hardware.wifi.supplicant.V1_0.IfaceType;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;

import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.RemoteException;
import android.os.HwRemoteBinder;
import android.util.Log;

import com.qualcomm.qti.server.qtiwifi.util.GeneralUtil.Mutable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Hal calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 */
public class QtiSupplicantStaIfaceAOSPHalHidlImpl implements IQtiSupplicantStaIfaceAOSPHal {
    private static final String TAG = "QtiSupplicantStaIfaceAOSPHalHidlImpl";
    public static final String HAL_INSTANCE_NAME = "default";

    private final Object mLock = new Object();

    // Supplicant HAL interface objects
    private IServiceManager mIServiceManager = null;
    private ISupplicant mISupplicant;
    private QtiSupplicantIface mQtiSupplicantIface;
    private HashMap<String, ISupplicantStaIface> mISupplicantStaIfaces = new HashMap<>();
    QtiWifiHandler mQtiWifiHandler;

    private final IServiceNotification mServiceNotificationCallback =
            new IServiceNotification.Stub() {
                public void onRegistration(String fqName, String name, boolean preexisting) {
                    synchronized (mLock) {
                        Log.i(TAG, "IServiceNotification.onRegistration for: " + fqName
                                + ", " + name + " preexisting=" + preexisting);
                        if (!initSupplicantService()) {
                            Log.e(TAG, "Initializing ISupplicant failed.");
                            supplicantServiceDiedHandler();
                        } else {
                            Log.i(TAG, "Completed initialization of ISupplicant.");
                        }
                    }
                }
            };
    private final HwRemoteBinder.DeathRecipient mServiceManagerDeathRecipient =
            cookie -> {
                synchronized (mLock) {
                    Log.w(TAG, "IServiceManager died: cookie=" + cookie);
                    supplicantServiceDiedHandler();
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                }
            };
    private final HwRemoteBinder.DeathRecipient mSupplicantDeathRecipient =
            cookie -> {
                synchronized (mLock) {
                    Log.w(TAG, "ISupplicant/ISupplicantStaIface died: cookie=" + cookie);
                    supplicantServiceDiedHandler();
                }
            };

    private boolean linkToServiceManagerDeath() {
        synchronized (mLock) {
            if (mIServiceManager == null) return false;
            try {
                if (!mIServiceManager.linkToDeath(mServiceManagerDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                    supplicantServiceDiedHandler();
                    mIServiceManager = null;
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IServiceManager.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    public QtiSupplicantStaIfaceAOSPHalHidlImpl(QtiSupplicantIface qtiSupplicantIface, QtiWifiHandler qtiWifiHandler) {
        mQtiSupplicantIface = qtiSupplicantIface;
        mQtiWifiHandler = qtiWifiHandler;
    }

    private boolean initSupplicantService() {
        synchronized (mLock) {
            try {
                mISupplicant = getSupplicantMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.getService exception: " + e);
                return false;
            } catch (NoSuchElementException e) {
                Log.e(TAG, "ISupplicant.getService exception: " + e);
                return false;
            }
            if (mISupplicant == null) {
                Log.e(TAG, "Got null ISupplicant service. Stopping supplicant HIDL startup");
                return false;
            }
            if (!linkToSupplicantDeath()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Registers a service notification for the ISupplicant service, which triggers initialization
     * of the ISupplicantStaIface
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        synchronized (mLock) {
            Log.i(TAG, "Registering ISupplicant service ready callback.");
            mISupplicant = null;
            mISupplicantStaIfaces.clear();
            if (mIServiceManager != null) {
                // Already have an IServiceManager and serviceNotification registered, don't
                // don't register another.
                return true;
            }
            try {
                mIServiceManager = getServiceManagerMockable();
                if (mIServiceManager == null) {
                    Log.e(TAG, "Failed to get HIDL Service Manager");
                    return false;
                }
                if (!linkToServiceManagerDeath()) {
                    return false;
                }
                if (!mIServiceManager.registerForNotifications(
                        ISupplicant.kInterfaceName, "", mServiceNotificationCallback)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + ISupplicant.kInterfaceName);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for "
                        + "ISupplicant service: " + e);
                supplicantServiceDiedHandler();
            }
            return true;
        }
    }

    private boolean linkToSupplicantDeath() {
        synchronized (mLock) {
            if (mISupplicant == null) return false;
            try {
                if (!mISupplicant.linkToDeath(mSupplicantDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on ISupplicant");
                    supplicantServiceDiedHandler();
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    private boolean linkToSupplicantStaIfaceDeath(ISupplicantStaIface iface) {
        synchronized (mLock) {
            if (iface == null) return false;
            try {
                if (!iface.linkToDeath(mSupplicantDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on ISupplicantStaIface");
                    supplicantServiceDiedHandler();
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    private boolean registerCallbackV1_4(
            android.hardware.wifi.supplicant.V1_4.ISupplicantStaIface iface,
            android.hardware.wifi.supplicant.V1_4.ISupplicantStaIfaceCallback callback) {
        synchronized (mLock) {
            String methodStr = "registerCallback_1_4";

            if (iface == null) return false;
            try {
                android.hardware.wifi.supplicant.V1_4.SupplicantStatus status =
                        iface.registerCallback_1_4(callback);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    protected ISupplicantStaIface getStaIfaceMockable(ISupplicantIface iface) {
        synchronized (mLock) {
            return ISupplicantStaIface.asInterface(iface.asBinder());
        }
    }

    protected android.hardware.wifi.supplicant.V1_4.ISupplicantStaIface
            getStaIfaceMockableV1_4(ISupplicantIface iface) {
        synchronized (mLock) {
            return android.hardware.wifi.supplicant.V1_4.ISupplicantStaIface
                    .asInterface(iface.asBinder());
        }
    }

    private ISupplicantStaIface setupStaIface(@NonNull String ifaceName,
            @NonNull ISupplicantIface ifaceHwBinder) throws RemoteException {
        /* Prepare base type for later cast. */
        ISupplicantStaIface iface = getStaIfaceMockable(ifaceHwBinder);

        if (!isV1_4()) return null;

        android.hardware.wifi.supplicant.V1_4.ISupplicantStaIfaceCallback.Stub callbackV14 =
                new android.hardware.wifi.supplicant.V1_4.ISupplicantStaIfaceCallback.Stub() {
                    @Override
                    public void onNetworkAdded(int id) {
                    }

                    @Override
                    public void onNetworkRemoved(int id) {
                    }

                    @Override
                    public void onStateChanged(int newState, byte[/* 6 */] bssid, int id,
                           ArrayList<Byte> ssid) {}

                    @Override
                    public void onAnqpQueryDone_1_4(byte[/* 6 */] bssid,
                           AnqpData data,
                           Hs20AnqpData hs20Data) {}

                    @Override
                    public void onAnqpQueryDone(byte[/* 6 */] bssid,
                           android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback.AnqpData data,
                           Hs20AnqpData hs20Data) {
                    }

                    @Override
                    public void onHs20IconQueryDone(byte[/* 6 */] bssid, String fileName,
                           ArrayList<Byte> data) {
                    }

                    @Override
                    public void onHs20SubscriptionRemediation(byte[/* 6 */] bssid,
                           byte osuMethod, String url) {
                    }

                    @Override
                    public void onHs20DeauthImminentNotice(byte[/* 6 */] bssid, int reasonCode,
                           int reAuthDelayInSec, String url) {
                    }

                    @Override
                    public void onDisconnected(byte[/* 6 */] bssid, boolean locallyGenerated,
                           int reasonCode) {
                        Log.d(TAG, "onDisconnected " + "bssid " + bssid +
                            " locallyGenerated " + locallyGenerated + " reason code " + reasonCode);
                        mQtiWifiHandler.notifyClientsOnNetworkDisconnect(bssid, reasonCode, locallyGenerated);
                    }

                    @Override
                    public void onAssociationRejected_1_4(AssociationRejectionData assocRejectData) {
                    }

                    @Override
                    public void onAssociationRejected(byte[/* 6 */] bssid, int statusCode,
                           boolean timedOut) {
                    }

                    @Override
                    public void onAuthenticationTimeout(byte[/* 6 */] bssid) {
                    }

                    @Override
                    public void onBssidChanged(byte reason, byte[/* 6 */] bssid) {
                    }

                    @Override
                    public void onEapFailure() {
                    }

                    @Override
                    public void onEapFailure_1_1(int code) {
                    }

                    @Override
                    public void onEapFailure_1_3(int code) {
                    }

                    @Override
                    public void onWpsEventSuccess() {
                    }

                    @Override
                    public void onWpsEventFail(byte[/* 6 */] bssid, short configError, short errorInd) {
                    }

                    @Override
                    public void onWpsEventPbcOverlap() {
                    }

                    @Override
                    public void onExtRadioWorkStart(int id) {
                    }

                    @Override
                    public void onExtRadioWorkTimeout(int id) {
                    }

                    @Override
                    public void onDppSuccessConfigReceived(ArrayList<Byte> ssid, String password,
                           byte[] psk, int securityAkm) {
                    }

                    @Override
                    public void onDppSuccessConfigSent() {
                    }

                    @Override
                    public void onDppProgress(int code) {
                    }

                    @Override
                    public void onDppFailure(int code) {
                    }

                    @Override
                    public void onPmkCacheAdded(long expirationTimeInSec, ArrayList<Byte> serializedEntry) {
                    }

                    @Override
                    public void onDppProgress_1_3(int code) {
                    }

                    @Override
                    public void onDppFailure_1_3(int code, String ssid, String channelList,
                           ArrayList<Short> bandList) {
                    }

                    @Override
                    public void onDppSuccess(int code) {
                    }

                    @Override
                    public void onBssTmHandlingDone(BssTmData tmData) {
                    }

                    @Override
                    public void onStateChanged_1_3(int newState, byte[/* 6 */] bssid, int id,
                           ArrayList<Byte> ssid, boolean filsHlpSent) {
                    }

                    @Override
                    public void onHs20TermsAndConditionsAcceptanceRequestedNotification(byte[/* 6 */] bssid,
                           String url) {
                    }

                    @Override
                    public void onNetworkNotFound(ArrayList<Byte> ssid) {
                    }
        };
        if (!registerCallbackV1_4(getStaIfaceMockableV1_4(iface), callbackV14)) {
            throw new RemoteException("Init StaIface V1_4 failed.");
        }
        return iface;
    }

     private ISupplicantIface getIfaceV1_0(@NonNull String ifaceName) {
        synchronized (mLock) {
            if (mISupplicant == null) {
                Log.e(TAG, "ISupplicant is null");
                return null;
            }

            /** List all supplicant Ifaces */
            final ArrayList<ISupplicant.IfaceInfo> supplicantIfaces = new ArrayList<>();
            try {
                mISupplicant.listInterfaces((SupplicantStatus status,
                                             ArrayList<ISupplicant.IfaceInfo> ifaces) -> {
                    if (status.code != SupplicantStatusCode.SUCCESS) {
                        Log.e(TAG, "Getting Supplicant Interfaces failed: " + status.code);
                        return;
                    }
                    supplicantIfaces.addAll(ifaces);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.listInterfaces exception: " + e);
                handleRemoteException(e, "listInterfaces");
                return null;
            }
            if (supplicantIfaces.size() == 0) {
                Log.e(TAG, "Got zero HIDL supplicant ifaces. Stopping supplicant HIDL startup.");
                return null;
            }
            Mutable<ISupplicantIface> supplicantIface = new Mutable<>();
            for (ISupplicant.IfaceInfo ifaceInfo : supplicantIfaces) {
                if (ifaceInfo.type == IfaceType.STA && ifaceName.equals(ifaceInfo.name)) {
                    try {
                        mISupplicant.getInterface(ifaceInfo,
                                (SupplicantStatus status, ISupplicantIface iface) -> {
                                    if (status.code != SupplicantStatusCode.SUCCESS) {
                                        Log.e(TAG, "Failed to get ISupplicantIface " + status.code);
                                        return;
                                    }
                                    supplicantIface.value = iface;
                                });
                    } catch (RemoteException e) {
                        Log.e(TAG, "ISupplicant.getInterface exception: " + e);
                        handleRemoteException(e, "getInterface");
                        return null;
                    }
                    break;
                }
            }
            if (supplicantIface != null)
                return supplicantIface.value;
            return null;
        }
    }

    private boolean isV1_0() {
        synchronized (mLock) {
            try {
                return (getSupplicantMockable() != null);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.getService exception: " + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Setup a STA interface for the specified iface name.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    public boolean setupIface(@NonNull String ifaceName) {
        final String methodStr = "setupIface";
        if (checkSupplicantStaIfaceAndLogFailure(ifaceName, methodStr) != null) return false;
        ISupplicantIface ifaceHwBinder = null;

        if (isV1_0()) {
            Log.i(TAG, "Try to get HIDL@1.0 interface");
            ifaceHwBinder = getIfaceV1_0(ifaceName);
        }
        if (ifaceHwBinder == null) {
            Log.e(TAG, "setupIface got null iface");
            return false;
        }

        try {
            ISupplicantStaIface iface = setupStaIface(ifaceName, ifaceHwBinder);
            mISupplicantStaIfaces.put(ifaceName, iface);
        } catch (RemoteException e) {
            Log.e(TAG, "setup StaIface failed: " + e.toString());
            return false;
        }

        return true;
    }

    /**
     * Uses the IServiceManager to check if the device is running V1_4 of the HAL from the VINTF for
     * the device.
     * @return true if supported, false otherwise.
     */
    private boolean isV1_4() {
        return checkHalVersionByInterfaceName(
                android.hardware.wifi.supplicant.V1_4.ISupplicant.kInterfaceName);
    }

    private boolean checkHalVersionByInterfaceName(String interfaceName) {
        if (interfaceName == null) {
            return false;
        }
        synchronized (mLock) {
            if (mIServiceManager == null) {
                Log.e(TAG, "checkHalVersionByInterfaceName: called but "
                        + "mServiceManager is null");
                return false;
            }
            try {
                return (mIServiceManager.getTransport(
                        interfaceName,
                        HAL_INSTANCE_NAME)
                        != IServiceManager.Transport.EMPTY);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while operating on IServiceManager: " + e);
                handleRemoteException(e, "getTransport");
                return false;
            }
        }
    }

    /**
     * Helper method to look up the network object for the specified iface.
     */
    private ISupplicantStaIface getStaIface(@NonNull String ifaceName) {
        return mISupplicantStaIfaces.get(ifaceName);
    }

    /**
     * Returns false if SupplicantStaIface is null, and logs failure to call methodStr
     */
    private ISupplicantStaIface checkSupplicantStaIfaceAndLogFailure(
            @NonNull String ifaceName, final String methodStr) {
        synchronized (mLock) {
            ISupplicantStaIface iface = getStaIface(ifaceName);
            if (iface == null) {
                Log.e(TAG, "Can't call " + methodStr + ", ISupplicantStaIface is null for iface="
                        + ifaceName);
                return null;
            }
            return iface;
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure(SupplicantStatus status,
            final String methodStr) {
        synchronized (mLock) {
            if (status == null || status.code != SupplicantStatusCode.SUCCESS) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + " failed: " + status);
                return false;
            } else {
                Log.d(TAG, "ISupplicantStaIface." + methodStr + " succeeded");
                return true;
            }
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure(
            android.hardware.wifi.supplicant.V1_4.SupplicantStatus status,
            final String methodStr) {
        synchronized (mLock) {
            if (status == null
                    || status.code
                    != android.hardware.wifi.supplicant.V1_4.SupplicantStatusCode.SUCCESS) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + " failed: " + status);
                return false;
            } else {
                Log.d(TAG, "ISupplicantStaIface." + methodStr + " succeeded");
                return true;
            }
        }
    }
    /**
     * Returns true if provided supplicant vendor status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkSupplicantStatusAndLogFailure(SupplicantStatus status,
            final String methodStr) {
        synchronized (mLock) {
            if (status.code != SupplicantStatusCode.SUCCESS) {
                Log.e(TAG, "ISupplicant " + methodStr + " failed: " + status);
                return false;
            } else {
                Log.d(TAG, "ISupplicant " + methodStr + " succeeded");
                return true;
            }
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

    /**
     * Handle supplicantvendor death.
     */
    private void supplicantServiceDiedHandler() {
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantStaIfaces.clear();
        }
    }

    /**
     * Indicates whether the HIDL service is declared. Uses the IServiceManager to check
     * if the device is running a version >= V1_0 of the HAL from the VINTF for the device.
     */
    public static boolean serviceDeclared() {
        try {
            IServiceManager serviceManager = IServiceManager.getService();
            String interfaceName = android.hardware.wifi.supplicant.V1_0.ISupplicant.kInterfaceName;
            if (serviceManager.getTransport(interfaceName, HAL_INSTANCE_NAME)
                    != IServiceManager.Transport.EMPTY) {
                return true;
            }
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to check for existence of HIDL service.");
            return false;
        }
    }

    /**
     * Wrapper functions to access static HAL methods, created to be mockable in unit tests
     */
    protected IServiceManager getServiceManagerMockable() throws RemoteException {
        synchronized (mLock) {
            return IServiceManager.getService();
        }
    }

    protected ISupplicant getSupplicantMockable() throws RemoteException, NoSuchElementException {
        synchronized (mLock) {
            ISupplicant iSupplicant = ISupplicant.getService();
            if (iSupplicant == null) {
                throw new NoSuchElementException("Cannot get root service.");
            }
            return iSupplicant;
        }
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            Log.e(TAG, "ISupplicantStaIface." + methodStr + " failed with exception", e);
        }
    }
}

