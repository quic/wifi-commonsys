/* Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import com.qualcomm.qti.server.qtiwifi.QtiWifiServiceImpl.WifiHalListener;

/**
 * HAL calls to set up/tear down the hostapd daemon and make requests
 * related to station mode. Uses the Vendor AIDL hostapd interface.
 */
public class QtiHostapdHalHidlImpl implements IQtiHostapdHal {

    /**
     * Checks whether the IHostapdVendor service is declared, and therefore should be available.
     *
     * @return true if the IHostapdVendor service is declared
     */
    public boolean initialize() {
        return false;
    }

    /**
     * Indicates whether the AIDL service is declared
     */
    public static boolean serviceDeclared() {
        return false;
    }

    /**
     * run Driver command
     *
     * @param command Driver Command
     * @return status
     */
    public String doDriverCmd(String iface, String command)
    {
        return null;
    }

    /**
     * List active SAP instances
     *
     * @return available SAP instances
     */
    public String[] listVendorInterfaces() {
        return null;
    }

    /**
     * Register Hal listener for vendor events
     */
    public void registerWifiHalListener(WifiHalListener listener) {
    }
}

