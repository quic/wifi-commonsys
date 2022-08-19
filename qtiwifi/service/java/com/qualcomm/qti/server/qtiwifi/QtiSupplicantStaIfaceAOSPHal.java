/* Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import android.annotation.NonNull;
import android.util.Log;

public class QtiSupplicantStaIfaceAOSPHal {
    private static final String TAG = "QtiSupplicantStaIfaceAOSPHal";

    private final Object mLock = new Object();

    // Vendor HAL interface object - might be implemented by HIDL or AIDL
    private IQtiSupplicantStaIfaceAOSPHal mQtiStaIfaceHal;
    private QtiSupplicantIface mQtiSupplicantIface;
    private QtiWifiHandler mQtiWifiHandler;

    public QtiSupplicantStaIfaceAOSPHal(QtiSupplicantIface qtiSupplicantIface, QtiWifiHandler qtiWifiHandler) {
        mQtiSupplicantIface = qtiSupplicantIface;
        mQtiWifiHandler = qtiWifiHandler;
        mQtiStaIfaceHal = createStaIfaceAOSPHalMockable();
        if (mQtiStaIfaceHal == null) {
            Log.e(TAG, "Failed to get internal ISupplicantVendorStaIfaceHal instance.");
        }
    }

    /**
     * Initialize the STA Iface HAL. Creates the internal IQtiSupplicantStaIfaceHal
     * object and calls its initialize method.
     *
     * @return true if the initialization succeeded
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mQtiStaIfaceHal == null) {
                Log.e(TAG, "Internal ISupplicantStaIfaceHal instance does not exist.");
                return false;
            }
            if (!mQtiStaIfaceHal.initialize()) {
                Log.e(TAG, "Failed to init ISupplicantStaIfaceHal.");
                return false;
            }
            return true;
        }
    }

    /**
     * Wrapper function to create the ISupplicantStaIfaceHal object.
     * Created to be mockable in unit tests.
     */
    private IQtiSupplicantStaIfaceAOSPHal createStaIfaceAOSPHalMockable() {
        synchronized (mLock) {
            if (QtiSupplicantStaIfaceAOSPHalAidlImpl.serviceDeclared()) {
                Log.i(TAG, "Initializing QtiSupplicantStaIfaceAOSPHal using AIDL implementation.");
                return new QtiSupplicantStaIfaceAOSPHalAidlImpl(mQtiSupplicantIface, mQtiWifiHandler);
            } else if (QtiSupplicantStaIfaceAOSPHalHidlImpl.serviceDeclared()) {
                Log.i(TAG, "Initializing QtiSupplicantStaIfaceAOSPHal using HIDL implementation.");
                return new QtiSupplicantStaIfaceAOSPHalHidlImpl(mQtiSupplicantIface, mQtiWifiHandler);
            }
            Log.e(TAG, "No HIDL or AIDL service available for SupplicantStaIfaceHal.");
            return null;
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
            if (mQtiStaIfaceHal == null) {
                Log.e(TAG, "setupIface failed, mQtiStaIfaceHal is null");
                return false;
            }
            return mQtiStaIfaceHal.setupIface(ifaceName);
        }
    }
}
