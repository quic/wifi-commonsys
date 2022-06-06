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

import android.annotation.NonNull;
import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import vendor.qti.hardware.wifi.supplicant.V2_0.ISupplicantVendor;
import vendor.qti.hardware.wifi.supplicant.V2_0.ISupplicantVendorIface;
import vendor.qti.hardware.wifi.supplicant.V2_0.ISupplicantVendorStaIface;
import android.hardware.wifi.supplicant.V1_0.IfaceType;
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
import java.util.NoSuchElementException;

/**
 * Hal calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 */
public class QtiSupplicantStaIfaceHalHidlImpl implements IQtiSupplicantStaIfaceHal {
    private static final String TAG = "QtiSupplicantStaIfaceHalHidlImp";
    public static final String HAL_INSTANCE_NAME = "default";

    private final Object mLock = new Object();

    // Supplicant HAL interface objects
    private IServiceManager mIServiceManager = null;
    private ISupplicantVendor mISupplicantVendor;
    private HashMap<String, ISupplicantVendorStaIface> mISupplicantVendorStaIfaces = new HashMap<>();

    private final IServiceNotification mServiceNotificationCallback =
            new IServiceNotification.Stub() {
                public void onRegistration(String fqName, String name, boolean preexisting) {
                    synchronized (mLock) {
                        Log.i(TAG, "IServiceNotification.onRegistration for: " + fqName
                                + ", " + name + " preexisting=" + preexisting);
                        if (!initSupplicantVendorService()) {
                            Log.e(TAG, "Initializing SupplicantVendor failed.");
                            supplicantVendorServiceDiedHandler();
                        } else {
                            Log.i(TAG, "Completed initialization of ISupplicantVendor.");
                        }
                    }
                }
            };
    private final HwRemoteBinder.DeathRecipient mServiceManagerDeathRecipient =
            cookie -> {
                synchronized (mLock) {
                    Log.w(TAG, "IServiceManager died: cookie=" + cookie);
                    supplicantVendorServiceDiedHandler();
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                }
            };
    private final HwRemoteBinder.DeathRecipient mSupplicantVendorDeathRecipient =
            cookie -> {
                synchronized (mLock) {
                    Log.w(TAG, "ISupplicantVendor/ISupplicantVendorStaIface died: cookie=" + cookie);
                    supplicantVendorServiceDiedHandler();
                }
            };

    private boolean linkToServiceManagerDeath() {
        synchronized (mLock) {
            if (mIServiceManager == null) return false;
            try {
                if (!mIServiceManager.linkToDeath(mServiceManagerDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                    supplicantVendorServiceDiedHandler();
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

    /**
     * Registers a service notification for the ISupplicantVendor service, which triggers initialization
     * of the ISupplicantVendorStaIface
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        synchronized (mLock) {
            Log.i(TAG, "Registering ISupplicantVendor service ready callback.");
            mISupplicantVendor = null;
            mISupplicantVendorStaIfaces.clear();
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
                        ISupplicantVendor.kInterfaceName, "", mServiceNotificationCallback)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + ISupplicantVendor.kInterfaceName);
                    mIServiceManager = null;
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for "
                        + "ISupplicantVendor service: " + e);
                supplicantVendorServiceDiedHandler();
                mIServiceManager = null;
                return false;
            }

            if (isSupportedInternal()) {
                return initSupplicantVendorService();
            }

            return false;
        }
    }

    private boolean linkToSupplicantVendorDeath() {
        synchronized (mLock) {
            if (mISupplicantVendor == null) return false;
            try {
                if (!mISupplicantVendor.linkToDeath(mSupplicantVendorDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on ISupplicantVendor");
                    supplicantVendorServiceDiedHandler();
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantVendor.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    /**
     * Check if the device is running V2_0 supplicant vendor service.
     * @return
     */
    private boolean isVendor_2_0() {
        synchronized (mLock) {
            try {
                return (getSupplicantVendorMockable() != null);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantVendor.getService exception: " + e);
                supplicantVendorServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Returns false if SupplicantVendorStaIface is null, and logs failure to call methodStr
     */
    private ISupplicantVendorStaIface checkSupplicantVendorStaIfaceAndLogFailure(
            @NonNull String ifaceName, final String methodStr) {
        synchronized (mLock) {
            ISupplicantVendorStaIface iface = getVendorStaIface(ifaceName);
            if (iface == null) {
                Log.e(TAG, "Can't call " + methodStr + ", ISupplicantVendorStaIface is null");
                return null;
            }
            return iface;
        }
    }

    protected ISupplicantVendorStaIface getVendorStaIfaceMockable(ISupplicantVendorIface iface) {
        synchronized (mLock) {
            return ISupplicantVendorStaIface.asInterface(iface.asBinder());
        }
    }

    private boolean linkToSupplicantVendorStaIfaceDeath(ISupplicantVendorStaIface iface) {
        synchronized (mLock) {
            if (iface == null) return false;
            try {
                if (!iface.linkToDeath(mSupplicantVendorDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on ISupplicantVendorStaIface");
                    supplicantVendorServiceDiedHandler();
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantVendorStaIface.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    /**
     * Setup a Vendor STA interface for the specified iface name.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    public boolean setupVendorIface(@NonNull String ifaceName) {
        final String methodStr = "setupVendorIface";
        if (checkSupplicantVendorStaIfaceAndLogFailure(ifaceName, methodStr) != null) {
            Log.e(TAG, "Already created vendor setupinterface");
            return true;
        }
        ISupplicantVendorIface VendorIfaceHwBinder = null;

        if (isVendor_2_0()) {
            Log.i(TAG, "Try to get Vendor HIDL@2.0 interface");
            VendorIfaceHwBinder = getVendorIfaceV2_0(ifaceName);
        }
        if (VendorIfaceHwBinder == null) {
            Log.e(TAG, "Failed to get vendor iface binder");
            return false;
        }

        ISupplicantVendorStaIface vendor_iface = getVendorStaIfaceMockable(VendorIfaceHwBinder);
        if (vendor_iface == null) {
            Log.e(TAG, "Failed to get ISupplicantVendorStaIface proxy");
            return false;
        }
        else
            Log.i(TAG, "Successful get Vendor sta interface");

        if (!linkToSupplicantVendorStaIfaceDeath(vendor_iface)) {
            return false;
        }

        if (vendor_iface != null) {
            mISupplicantVendorStaIfaces.put(ifaceName, vendor_iface);
        }
        return true;
    }

    /**
     * Returns true if provided supplicant vendor status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkSupplicantVendorStatusAndLogFailure(SupplicantStatus status,
            final String methodStr) {
        synchronized (mLock) {
            if (status.code != SupplicantStatusCode.SUCCESS) {
                Log.e(TAG, "ISupplicantVendor." + methodStr + " failed: " + status);
                return false;
            } else {
                Log.d(TAG, "ISupplicantVendor." + methodStr + " succeeded");
                return true;
            }
        }
    }

    /**
     * Returns false if SupplicantVendor is null, and logs failure to call methodStr
     */
    private boolean checkSupplicantVendorAndLogFailure(final String methodStr) {
        synchronized (mLock) {
            if (mISupplicantVendor == null) {
                Log.e(TAG, "Can't call " + methodStr + ", ISupplicantVendor is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Returns true if provided Vendor status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkVendorStatusAndLogFailure(SupplicantStatus status,
            final String methodStr) {
        synchronized (mLock) {
            if (status.code != SupplicantStatusCode.SUCCESS) {
                Log.e(TAG, "ISupplicantVendorStaIface." + methodStr + " failed: " + status);
                return false;
            } else {
                Log.d(TAG, "ISupplicantVendorStaIface." + methodStr + " succeeded");
                return true;
            }
        }
    }

    /**
     * Get a Vendor STA interface for the specified iface name.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    private ISupplicantVendorIface getVendorIfaceV2_0(@NonNull String ifaceName) {
        synchronized (mLock) {
            /** List all supplicant Ifaces */
            final ArrayList<ISupplicant.IfaceInfo> supplicantIfaces = new ArrayList<>();
            try {
                final String methodStr = "listVendorInterfaces";
                if (!checkSupplicantVendorAndLogFailure(methodStr)) return null;
                mISupplicantVendor.listVendorInterfaces((SupplicantStatus status,
                                             ArrayList<ISupplicant.IfaceInfo> ifaces) -> {
                    if (!checkSupplicantVendorStatusAndLogFailure(status, methodStr)) {
                        return;
                    }
                    supplicantIfaces.addAll(ifaces);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantVendor.listInterfaces exception: " + e);
                supplicantVendorServiceDiedHandler();
                return null;
            }
            if (supplicantIfaces.size() == 0) {
                Log.e(TAG, "Got zero HIDL supplicant vendor ifaces. Stopping supplicant vendor HIDL startup.");
                return null;
            }
            Mutable<ISupplicantVendorIface> supplicantVendorIface = new Mutable<>();
            for (ISupplicant.IfaceInfo ifaceInfo : supplicantIfaces) {
                if (ifaceInfo.type == IfaceType.STA && ifaceName.equals(ifaceInfo.name)) {
                    try {
                        final String methodStr = "getVendorInterface";
                        if (!checkSupplicantVendorAndLogFailure(methodStr)) return null;
                        mISupplicantVendor.getVendorInterface(ifaceInfo,
                                (SupplicantStatus status, ISupplicantVendorIface iface) -> {
                                    if (!checkSupplicantVendorStatusAndLogFailure(status, methodStr)) {
                                        return;
                                    }
                                    supplicantVendorIface.value = iface;
                                });
                    } catch (RemoteException e) {
                        Log.e(TAG, "ISupplicantVendor.getInterface exception: " + e);
                        supplicantVendorServiceDiedHandler();
                        return null;
                    }
                    break;
                }
            }
            return supplicantVendorIface.value;
        }
    }

    /**
     * Uses the IServiceManager to query if the SupplicantVendor HAL is present in the VINTF for the device
     * or not.
     * @return true if supported, false otherwise.
     */
    private boolean isSupportedInternal() {
        synchronized (mLock) {
            if (mIServiceManager == null) {
                Log.e(TAG, "isSupported: called but mIServiceManager is null!?");
                return false;
            }
            try {
                return (mIServiceManager.getTransport(vendor.qti.hardware.wifi.supplicant.V2_2.ISupplicantVendor.kInterfaceName, HAL_INSTANCE_NAME)
                        != IServiceManager.Transport.EMPTY);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Exception while operating on IServiceManager: " + e);
                return false;
            }
        }
    }

    /**
     * Initialize the ISupplicantVendor object.
     * @return true on success, false otherwise.
     */
    private boolean initSupplicantVendorService() {
        synchronized (mLock) {
            try {
            // Discovering supplicantvendor service
                mISupplicantVendor = getSupplicantVendorMockable();
                if (mISupplicantVendor != null) {
                   Log.e(TAG, "Discover ISupplicantVendor service successfull");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantVendor.getService exception: " + e);
                return false;
            }
            if (mISupplicantVendor == null) {
                Log.e(TAG, "Got null ISupplicantVendor service. Stopping supplicantVendor HIDL startup");
                return false;
            }
            // check mISupplicantVendor service and trigger death service
            if (!linkToSupplicantVendorDeath()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Handle supplicantvendor death.
     */
    private void supplicantVendorServiceDiedHandler() {
        synchronized (mLock) {
            mISupplicantVendor = null;
            mISupplicantVendorStaIfaces.clear();
        }
    }

    /**
     * Indicates whether the HIDL service is declared. Uses the IServiceManager to check
     * if the device is running a version >= V1_0 of the HAL from the VINTF for the device.
     */
    public static boolean serviceDeclared() {
        try {
            IServiceManager serviceManager = IServiceManager.getService();
            String interfaceName = vendor.qti.hardware.wifi.supplicant.V2_2.ISupplicantVendor.kInterfaceName;
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

    protected ISupplicantVendor getSupplicantVendorMockable() throws RemoteException {
        synchronized (mLock) {
            try {
                return ISupplicantVendor.getService();
            } catch (NoSuchElementException e) {
                Log.e(TAG, "Failed to get ISupplicantVendor", e);
                return null;
            }
        }
    }

    /**
     * Helper method to look up the vendor_iface object for the specified iface.
     */
    private ISupplicantVendorStaIface getVendorStaIface(@NonNull String ifaceName) {
        return mISupplicantVendorStaIfaces.get(ifaceName);
    }

    protected vendor.qti.hardware.wifi.supplicant.V2_2.ISupplicantVendorStaIface
        getSupplicantVendorStaIfaceV2_2Mockable(ISupplicantVendorStaIface vendorIfaceV2_0) {
        if (vendorIfaceV2_0 == null) return null;
        return vendor.qti.hardware.wifi.supplicant.V2_2.ISupplicantVendorStaIface.castFrom(
                vendorIfaceV2_0);
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            Log.e(TAG, "ISupplicantVendorStaIface." + methodStr + " failed with exception", e);
        }
    }

     /**
     * run Driver command
     *
     * @param ifaceName Interface Name
     * @param command Driver Command
     */
    public String doDriverCmd(String command)
    {
        synchronized (mLock) {
            final String methodStr = "doDriverCmd";
            final Mutable<String> reply = new Mutable<>();

            reply.value = "";
            ISupplicantVendorStaIface vendorIfaceV2_0 = getVendorStaIface("wlan0");
            if (vendorIfaceV2_0 == null) {
                Log.e(TAG, "Can't call " + methodStr + ", ISupplicantVendorStaIface is null");
                return null;
            }

            vendor.qti.hardware.wifi.supplicant.V2_2.ISupplicantVendorStaIface vendorIfaceV2_2;
            vendorIfaceV2_2 = getSupplicantVendorStaIfaceV2_2Mockable(vendorIfaceV2_0);
            if (vendorIfaceV2_2 == null) {
                Log.e(TAG, "Can't call " + methodStr + ", V2_2.ISupplicantVendorStaIface is null");
                return null;
            }

            try {
                vendorIfaceV2_2.doDriverCmd(command,
                        (SupplicantStatus status, String rply) -> {
                        if(checkVendorStatusAndLogFailure(status, methodStr)) {
                            reply.value = rply;
                     }
                });
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
            return reply.value;
         }
    }

}

