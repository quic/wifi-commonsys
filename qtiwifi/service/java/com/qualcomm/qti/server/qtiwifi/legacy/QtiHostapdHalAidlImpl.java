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
public class QtiHostapdHalAidlImpl implements IQtiHostapdHal {

    public QtiHostapdHalAidlImpl() {
    }

    public boolean initialize() {
        return false;
    }

    public boolean getHostapdVendorInstance() {

        return false;
    }

    public static boolean serviceDeclared() {
        return false;
    }

    public String doDriverCmd(String iface, String command)
    {
        return null;
    }

    public String[] listVendorInterfaces() {
        return null;
    }

    public void registerWifiHalListener(WifiHalListener listener) {
    }
}

