/* Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import android.annotation.NonNull;
import com.qualcomm.qti.server.qtiwifi.QtiWifiServiceImpl.WifiHalListener;

/** Abstraction of Qti Supplicant STA Iface HAL interface */
interface IQtiSupplicantStaIfaceHal {
    /**
     * Begin initializing the IQtiSupplicantStaIfaceHal object. Specific initialization
     * logic differs between the HIDL and AIDL implementations.
     *
     * @return true if the initialization routine was successful
     */
    boolean initialize();

    /**
     * Signals whether initialization started successfully.
     */
    boolean isInitializationStarted();

    /**
     * Setup a STA interface for the specified iface name.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    boolean setupVendorIface(@NonNull String ifaceName);

    /**
     * Run Driver command
     *
     * @param command Driver Command.
     * @return status/reply based on command type.
     */
    String doDriverCmd(String command);

    /**
     * List available STA interfaces
     *
     * @return active STA instances
     */
    String[] listVendorInterfaces();

    /**
     * Register Hal listener for vendor events
     */
    void registerWifiHalListener(WifiHalListener listener);
}
