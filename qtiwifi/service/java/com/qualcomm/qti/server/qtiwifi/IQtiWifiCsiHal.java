/*
 * Copyright (c) 2023 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;
import android.annotation.NonNull;
import android.os.Binder;
import android.os.IBinder;

import com.qualcomm.qti.qtiwifi.ICsiCallback;

/** Abstraction of Qti Wificsi HAL interface */
interface IQtiWifiCsiHal {
    /**
     * Begin initializing the IQtiWifiCsiHal object. Specific initialization
     * logic differs between the HIDL and AIDL implementations.
     *
     * @return true if the initialization routine was successful
     */
    boolean initialize();

    /**
     * Registers a callback for csi events.
     *
     * @param binder
     * @param callback CsiCallback for the application to receive updates about
     * csi events.
     * @param callbackIdentifier to be used to register callback.
     *
     */
    void registerCsiCallback(@NonNull IBinder binder,
         @NonNull ICsiCallback callback, @NonNull int callbackIdentifier);

    /**
     * unregisters a callback for csi events.
     *
     * @param callbackIdentifier to be used for registered callback.
     *
     */
    void unregisterCsiCallback(int callbackIdentifier);

    /**
     * API to start CSI data collection.
     */
    void startCsi();

    /**
     * API to stop CSI data collection.
     */
    void stopCsi();
}
