/*
 * Copyright (c) 2023 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import vendor.qti.hardware.wifi.wificfr.IWificfr;
import vendor.qti.hardware.wifi.wificfr.CaptureStatusCode;
import vendor.qti.hardware.wifi.wificfr.CaptureStatus;
import vendor.qti.hardware.wifi.wificfr.IWificfrDataCallback;
import com.qualcomm.qti.qtiwifi.ICsiCallback;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Log;

import java.util.HashMap;

public class QtiWifiCsiHalAidlImpl implements IQtiWifiCsiHal {
    private static final String TAG = "QtiWifiCsiHalAidlImpl";
    private static final String HAL_INSTANCE_NAME = IWificfr.DESCRIPTOR + "/default";

    private final Object mLock = new Object();
    private boolean mServiceDeclared = false;

    //  AIDL interface objects
    private IWificfr mIWificfr;
    private final IWificfrDataCallback mIWificfrDataCallback;

    /* Limit on number of registered csi callbacks to track and prevent potential memory leak */
    private static final int NUM_CSI_CALLBACKS_WTF_LIMIT = 20;
    private final HashMap<Integer, ICsiCallback> mRegisteredCsiCallbacks;
    private ICsiCallback mCsiCallback;

    private WificfrDeathRecipient mWificfrDeathRecipient;
    private class WificfrDeathRecipient implements DeathRecipient {
        @Override
        public void binderDied() {
                synchronized (mLock) {
                    Log.w(TAG, "IWificfr binder died.");
                    wificfrServiceDiedHandler();
                }
        }
    }

    /**
     * Callback for events on IWifiCfr interface.
     */
    private class WificfrDataCallback extends IWificfrDataCallback.Stub {
        @Override
        public void onCfrDataAvailable(byte[] info) {
            Log.i(TAG, "onCfrDataAvailable called");
            if (mCsiCallback != null) {
                try {
                    mCsiCallback.onCsiUpdate(info);
                } catch (RemoteException e) {
                    Log.e(TAG, "onCsiUpdate " + e);
                }

            }
        }

        @Override
        public String getInterfaceHash() {
            return null;
        }

        @Override
        public int getInterfaceVersion() {
            return -1;
        }
    }

    public QtiWifiCsiHalAidlImpl() {
        mWificfrDeathRecipient = new WificfrDeathRecipient();
        mIWificfrDataCallback = new WificfrDataCallback();
        mRegisteredCsiCallbacks = new HashMap<>();
    }

    /**
     * Checks whether the IHostapdVendor service is declared, and therefore should be available.
     *
     * @return true if the IHostapdVendor service is declared
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mIWificfr != null) {
                Log.i(TAG, "Service is already initialized, skipping initialize method");
                return true;
            }
            Log.i(TAG, "Checking for IWificfr service.");
            mServiceDeclared = serviceDeclared();

            return getWificfrInstance() && mServiceDeclared;
        }
    }

    /**
     * Wrapper functions to access HAL objects, created to be mockable in unit tests
     */
    protected IWificfr getWificfrMockable() {
        synchronized (mLock) {
            try {
                return IWificfr.Stub.asInterface(
                        ServiceManager.waitForDeclaredService(HAL_INSTANCE_NAME));
            } catch (Exception e) {
                Log.e(TAG, "Unable to get IWificfr service, " + e);
                return null;
            }
        }
    }

    protected IBinder getServiceBinderMockable() {
        synchronized (mLock) {
            if (mIWificfr == null) {
                return null;
            }
            return mIWificfr.asBinder();
        }
    }

    private boolean getWificfrInstance() {
        final String methodStr = "getWificfrInstance";
        if (mIWificfr != null) {
            Log.i(TAG, "Service is already initialized, skipping " + methodStr);
            return true;
        }

        mIWificfr = getWificfrMockable();
        if (mIWificfr == null) {
            Log.e(TAG, "Unable to obtain IWificfr binder.");
            return false;
        }
        Log.i(TAG, "Obtained IWificfr binder.");

        try {
            IBinder serviceBinder = getServiceBinderMockable();
            if (serviceBinder == null) {
                return false;
            }
            serviceBinder.linkToDeath(mWificfrDeathRecipient, 0);
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

    private void wificfrServiceDiedHandler() {
        synchronized (mLock) {
            mIWificfr = null;
        }
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            Log.e(TAG, "IWificfr." + methodStr + " failed with exception" + e);
        }
    }

    private void handleServiceSpecificException(ServiceSpecificException e, String methodStr) {
        synchronized (mLock) {
            Log.e(TAG, "IWificfr." + methodStr + " failed with exception" + e);
        }
    }

    /**
     * Returns false if WifiCfr is null, and logs failure to call methodStr
     */
    private boolean checkWifiCfrAndLogFailure(String methodStr) {
        synchronized (mLock) {
            if (mIWificfr == null) {
                Log.e(TAG, "Can't call " + methodStr + ", IWifiCfr is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure(CaptureStatus status,
            String methodStr) {
        synchronized (mLock) {
            if (status.code != CaptureStatusCode.SUCCESS) {
                Log.e(TAG, "IWifiCfr." + methodStr + " failed: " + status.code
                        + ", " + status.debugMessage);
                return false;
            } else {
                Log.d(TAG, "IWifiCfr." + methodStr + " succeeded");
                return true;
            }
        }
    }

    /**
     * register IWifiCfr event callback.
     *
     * @return true on success, false otherwise.
     */
    private boolean registerEventCallback() {
        synchronized (mLock) {
            final String methodStr = "registerEventCallback";
            if (!checkWifiCfrAndLogFailure(methodStr)) return false;

            try {
                mIWificfr.unregisterEventCallback(mIWificfrDataCallback);
                CaptureStatus status = mIWificfr.registerEventCallback(mIWificfrDataCallback);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * unregister IWifiCfr event callback.
     *
     * @param callback .
     * @return true on success, false otherwise.
     */
    private boolean unregisterEventCallback() {
        synchronized (mLock) {
            final String methodStr = "unregisterEventCallback";
            if (!checkWifiCfrAndLogFailure(methodStr)) return false;

            try {
                CaptureStatus status = mIWificfr.unregisterEventCallback(mIWificfrDataCallback);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    public void registerCsiCallback(IBinder binder, ICsiCallback callback,
            int callbackIdentifier) {
        // register for binder death
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                binder.unlinkToDeath(this, 0);
                mRegisteredCsiCallbacks.remove(callbackIdentifier);
            }
        };

        try {
            binder.linkToDeath(dr, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Error on linkToDeath - " + e);
            return;
        }

        mCsiCallback = callback;
        mRegisteredCsiCallbacks.put(callbackIdentifier, callback);

        if (mRegisteredCsiCallbacks.size() > NUM_CSI_CALLBACKS_WTF_LIMIT) {
            Log.e(TAG, "Too many csi callbacks: " + mRegisteredCsiCallbacks.size());
        }

    }

    public void unregisterCsiCallback(int callbackIdentifier) {
        if (mCsiCallback != null)
            mCsiCallback = null;

        mRegisteredCsiCallbacks.remove(callbackIdentifier);
    }

    public void startCsi() {
       final String methodStr = "startCsi";
       Log.i(TAG, "startCsi Called");

       try {
           if (!registerEventCallback()) {
               Log.e(TAG, "CFR tool is not running, make sure its running");
           } else {
               mIWificfr.csiCaptureStart();
               Log.i(TAG, "csiCaptureStart invoked through aidl client");
           }
       } catch (RemoteException e) {
           handleRemoteException(e, methodStr);
       } catch (ServiceSpecificException e) {
           handleServiceSpecificException(e, methodStr);
       }
    }

    public void stopCsi() {
       final String methodStr = "stopCsi";
       Log.i(TAG, "stopCsi Called");

       try {
           if (!unregisterEventCallback()) {
               Log.e(TAG, "CFR tool is not running, make sure its running");
           } else {
               mIWificfr.csiCaptureStop();
               Log.i(TAG, "csiCaptureStop invoked through aidl client");
           }
       } catch (RemoteException e) {
           handleRemoteException(e, methodStr);
       } catch (ServiceSpecificException e) {
           handleServiceSpecificException(e, methodStr);
       }
    }
}

