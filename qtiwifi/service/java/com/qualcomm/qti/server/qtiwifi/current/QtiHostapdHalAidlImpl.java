/* Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import vendor.qti.hardware.wifi.hostapd.IHostapdVendor;
import vendor.qti.hardware.wifi.hostapd.IHostapdVendorCallback;
import vendor.qti.hardware.wifi.hostapd.HostapdVendorStatusCode;
import vendor.qti.hardware.wifi.hostapd.VendorApInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.HwRemoteBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Log;

import com.qualcomm.qti.server.qtiwifi.util.GeneralUtil.Mutable;
import com.qualcomm.qti.server.qtiwifi.QtiWifiServiceImpl.WifiHalListener;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HAL calls to set up/tear down the hostapd daemon and make requests
 * related to station mode. Uses the Vendor AIDL hostapd interface.
 */
public class QtiHostapdHalAidlImpl implements IQtiHostapdHal {
    private static final String TAG = "QtiHostapdHalAidlImpl";
    private static final String HAL_INSTANCE_NAME = IHostapdVendor.DESCRIPTOR + "/default";

    private static final int MIN_PORT_NUM = 0;
    private static final int MAX_PORT_NUM = 65535;

    private final Object mLock = new Object();
    private boolean mVerboseLoggingEnabled = false;
    private boolean mServiceDeclared = false;
    private String mVendorIfaceName = null;
    private Set<String> mActiveInterfaces;
    private WifiHalListener mWifiHalListener;

    // hostapd AIDL interface objects
    private IHostapdVendor mIHostapdVendor = null;
    private HostapdDeathRecipient mHostapdVendorDeathRecipient;

    /**
     * Register Hal listener for vendor events
     */
    public void registerWifiHalListener(WifiHalListener listener) {
        mWifiHalListener = listener;
    }

    private class HostapdVendorCallback extends IHostapdVendorCallback.Stub {
        @Override
        public void onCtrlEvent(String ifaceName, String eventStr) {
            Log.i(TAG, ifaceName + ": " + eventStr);
            if (eventStr == null) return;
            if (mWifiHalListener == null) return;

            // CTRL-EVENT-THERMAL-CHANGED level=3
            if (eventStr.startsWith(QtiWifiServiceImpl.THERMAL_EVENT_STR)) {
                    Matcher match = QtiWifiServiceImpl.THERMAL_PATTERN.matcher(eventStr);
                if (match.find()) {
                    int level = Integer.parseInt(match.group(1));
                    mWifiHalListener.onThermalChanged(ifaceName, level);
                } else {
                    Log.e(TAG, "Could not parse event=" + eventStr);
                }
            }
        }

        @Override
        public void onApInstanceInfoChanged(VendorApInfo apInfo) {
            Log.i(TAG, "ApInfo changed" + apInfo);
            mActiveInterfaces.add(apInfo.apIfaceInstance);
        }

        @Override
        public void onFailure(String ifname, String instanceName) {
            Log.i(TAG, "onFailure ifname=" + ifname + " instanceName=" + instanceName);
            mActiveInterfaces.remove(instanceName);
        }

        @Override
        public String getInterfaceHash() {
            return IHostapdVendorCallback.HASH;
        }

        @Override
        public int getInterfaceVersion() {
            return IHostapdVendorCallback.VERSION;
        }
    }

    private class HostapdDeathRecipient implements DeathRecipient {
        @Override
        public void binderDied() {
                synchronized (mLock) {
                    Log.w(TAG, "IHostapd binder died.");
                    HostapdVendorServiceDiedHandler();
                }
        }
    }

    public QtiHostapdHalAidlImpl() {
        mHostapdVendorDeathRecipient = new HostapdDeathRecipient();
        Log.i(TAG, "QtiHostapdHalAidlImpl() invoked");
    }

    /**
     * Checks whether the IHostapdVendor service is declared, and therefore should be available.
     *
     * @return true if the IHostapdVendor service is declared
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mIHostapdVendor != null) {
                Log.i(TAG, "Service is already initialized, skipping initialize method");
                return true;
            }
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "Checking for IHostapdVendor service.");
            }
            mServiceDeclared = serviceDeclared();
            getHostapdVendorInstance();
            return mServiceDeclared;
        }
    }

    /**
     * Wrapper functions to access HAL objects, created to be mockable in unit tests
     */
    protected IHostapdVendor getHostapdVendorMockable() {
        synchronized (mLock) {
            try {
                return IHostapdVendor.Stub.asInterface(
                        ServiceManager.waitForDeclaredService(HAL_INSTANCE_NAME));
            } catch (Exception e) {
                Log.e(TAG, "Unable to get IHostapdVendor service, " + e);
                return null;
            }
        }
    }

    protected IBinder getServiceBinderMockable() {
        synchronized (mLock) {
            if (mIHostapdVendor == null) {
                return null;
            }
            return mIHostapdVendor.asBinder();
        }
    }

    public boolean getHostapdVendorInstance() {

        final String methodStr = "getHostapdVendorInstance";
        if (mIHostapdVendor != null) {
            Log.i(TAG, "Service is already initialized, skipping " + methodStr);
            return true;
        }

        mIHostapdVendor = getHostapdVendorMockable();
        if (mIHostapdVendor == null) {
            Log.e(TAG, "Unable to obtain Ihostapd binder.");
            return false;
        }
        Log.i(TAG, "Obtained IHostapdVendor binder.");

        try {
            IBinder serviceBinder = getServiceBinderMockable();
            if (serviceBinder == null) {
                return false;
            }
            serviceBinder.linkToDeath(mHostapdVendorDeathRecipient, /* flags= */  0);
            IHostapdVendorCallback callback = new HostapdVendorCallback();
            mIHostapdVendor.registerHostapdVendorCallback(callback);
            return true;
        } catch (RemoteException e) {
            handleRemoteException(e, methodStr);
            return false;
        }
    }
    /**
     * Indicates whether the AIDL service is declared
     */
    public static boolean serviceDeclared() {
        return ServiceManager.isDeclared(HAL_INSTANCE_NAME);
    }

    /**
     * Returns false if hostapdVendor is null, and logs failure to call methodStr
     */
    private boolean checkhostapdVendorAndLogFailure(final String methodStr) {
        synchronized (mLock) {
            if (mIHostapdVendor == null) {
                Log.e(TAG, "Can't call " + methodStr + ", IHostapdVendor is null");
                return false;
            }
            return true;
        }
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            Log.e(TAG, "IHostapdVendor." + methodStr + " failed with exception", e);
        }
    }

    private void handleServiceSpecificException(ServiceSpecificException e, String methodStr) {
        synchronized (mLock) {
            Log.e(TAG, "IHostapdVendor." + methodStr + " failed with exception", e);
        }
    }

    /**
     * Handle hostapdvendor death.
     */
    private void HostapdVendorServiceDiedHandler() {
        synchronized (mLock) {
            mIHostapdVendor = null;
            mActiveInterfaces.clear();
        }
    }

    /**
     * run Driver command
     *
     * @param command Driver Command
     * @return status
     */
    public String doDriverCmd(String iface, String command)
    {
        synchronized (mLock) {
            final String methodStr = "doDriverCmd";
            final Mutable<String> reply = new Mutable<>();

            reply.value = "";

            try {
                reply.value = mIHostapdVendor.doDriverCmd(iface, "DRIVER " + command);
            } catch (RemoteException e) {
                Log.e(TAG, "doDriverCmd failed with RemoteException");
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                Log.e(TAG, "doDriverCmd failed with ServiceSpecificException");
                handleServiceSpecificException(e, methodStr);
            }
            return reply.value;
         }
    }

    /**
     * List active SAP instances
     *
     * @return available SAP instances
     */
    public String[] listVendorInterfaces() {
        synchronized (mLock) {
            String methodStr = "listVendorInterfaces";
            if (mActiveInterfaces != null && mActiveInterfaces.size() > 0) {
                return (String[])mActiveInterfaces.toArray();
            }
            try {
                return mIHostapdVendor.listVendorInterfaces();
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return null;
        }
    }
}

