/*
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import android.os.RemoteException;
import android.os.IBinder;
import android.util.Log;

import java.util.HashMap;

import com.qualcomm.qti.qtiwifi.IQtiInterfaceCallback;

/**
 * Main handler for all Asynchronous calls.
 */
public class QtiWifiHandler {
    private static final String TAG = "QtiWifiHandler";

    /* Limit on number of registered qti interface callbacks to track and prevent
     * potential memory leak */
    private static final int NUM_QTI_INTERFACE_CALLBACKS_WTF_LIMIT = 20;
    private final HashMap<Integer, IQtiInterfaceCallback> mRegisteredQtiInterfaceCallbacks;

    public QtiWifiHandler() {
        mRegisteredQtiInterfaceCallbacks = new HashMap<>();
    }

    public void registerCallback(IBinder binder, IQtiInterfaceCallback callback,
            int callbackIdentifier) {
        // register for binder death
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                binder.unlinkToDeath(this, 0);
                mRegisteredQtiInterfaceCallbacks.remove(callbackIdentifier);
            }
        };
        try {
            binder.linkToDeath(dr, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Error on linkToDeath - " + e);
            return;
        }

        mRegisteredQtiInterfaceCallbacks.put(callbackIdentifier, callback);

        if (mRegisteredQtiInterfaceCallbacks.size() > NUM_QTI_INTERFACE_CALLBACKS_WTF_LIMIT) {
            Log.e(TAG, "Too many qti callbacks: " + mRegisteredQtiInterfaceCallbacks.size());
        }

    }

    public void unregisterCallback(int callbackIdentifier) {
            mRegisteredQtiInterfaceCallbacks.remove(callbackIdentifier);
    }

    public void notifyClientsOnNetworkDisconnect(byte[] bssid, int reasonCode, boolean locallyGenerated) {
        for (IQtiInterfaceCallback callback : mRegisteredQtiInterfaceCallbacks.values()) {
             try {
                 callback.onNetworkDisconnect(bssid, reasonCode, locallyGenerated);
             } catch (RemoteException e) {
                 Log.e(TAG, "onNetworkDisconnect: remote exception -- " + e);
             }
        }
    }

    public void notifyClientsOnWifiAlert(int errorCode, byte[] buffer) {
        for (IQtiInterfaceCallback callback : mRegisteredQtiInterfaceCallbacks.values()) {
             try {
                 callback.onWifiAlert(errorCode, buffer);
             } catch (RemoteException e) {
                 Log.e(TAG, "onWifiAlert: remote exception -- " + e);
             }
        }
    }

}
