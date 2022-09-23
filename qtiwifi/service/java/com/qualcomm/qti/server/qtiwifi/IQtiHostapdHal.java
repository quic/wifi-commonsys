/* Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import com.qualcomm.qti.server.qtiwifi.QtiWifiServiceImpl.WifiHalListener;

/** Abstraction of Qti Hostapd HAL interface */
interface IQtiHostapdHal {
    /**
     * Begin initializing the IQtiHostapdHal object. Specific initialization
     * logic differs between the HIDL and AIDL implementations.
     *
     * @return true if the initialization routine was successful
     */
    boolean initialize();

    /**
     * Run driver command
     *
     * @param iface specified SAP instance.
     * @param command driver command.
     * @return command reply
     */
    String doDriverCmd(String iface, String command);

    /**
     * List available SAP interfaces
     *
     * @return active SAP instances
     */
    String[] listVendorInterfaces();

    /**
     * Register Hal listener for vendor events
     */
    void registerWifiHalListener(WifiHalListener listener);
}
