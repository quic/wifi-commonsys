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
 * Copyright (c) 2021-2022 Qualcomm Innovation Center, Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted (subject to the limitations in the
 * disclaimer below) provided that the following conditions are met:
 *
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *
 *   * Neither the name of Qualcomm Innovation Center nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE
 * GRANTED BY THIS LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT
 * HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.qualcomm.qti.server.qtiwifi;

import android.annotation.NonNull;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.HwRemoteBinder;
import android.os.RemoteException;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class QtiSupplicantStaIfaceHal {
    private static final String TAG = "QtiSupplicantStaIfaceHal";

    private final Object mLock = new Object();

    // Vendor HAL interface object - might be implemented by HIDL or AIDL
    private IQtiSupplicantStaIfaceHal mQtiStaIfaceHal;

    public QtiSupplicantStaIfaceHal() {
        Log.i(TAG, "constructor of QtiSupplicantStaIfaceHal called");
        mQtiStaIfaceHal = createVendorStaIfaceHalMockable();
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
                Log.e(TAG, "Internal ISupplicantVendorStaIfaceHal instance does not exist.");
                return false;
            }
            if (!mQtiStaIfaceHal.initialize()) {
                Log.e(TAG, "Failed to init ISupplicantVendorStaIfaceHal, stopping startup.");
                return false;
            }
            return true;
        }
    }

    /**
     * Wrapper function to create the ISupplicantStaIfaceHal object.
     * Created to be mockable in unit tests.
     */
    private IQtiSupplicantStaIfaceHal createVendorStaIfaceHalMockable() {
        synchronized (mLock) {
            if (QtiSupplicantStaIfaceHalAidlImpl.serviceDeclared()) {
                Log.i(TAG, "Initializing QtiSupplicantStaIfaceHal using AIDL implementation.");
                return new QtiSupplicantStaIfaceHalAidlImpl();
            } else if (QtiSupplicantStaIfaceHalHidlImpl.serviceDeclared()) {
                Log.i(TAG, "Initializing QtiSupplicantStaIfaceHal using HIDL implementation.");
                return new QtiSupplicantStaIfaceHalHidlImpl();
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
    public boolean setupVendorIface(@NonNull String ifaceName) {
        synchronized (mLock) {
            if (mQtiStaIfaceHal == null) {
                Log.e(TAG, "setupVendorIface failed, mQtiStaIfaceHal is null");
                return false;
            }
            return mQtiStaIfaceHal.setupVendorIface(ifaceName);
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
            if (mQtiStaIfaceHal == null) {
                return "QtiStaIfaceHal is null";
            }
            return mQtiStaIfaceHal.doDriverCmd(command);
        }
    }

}
