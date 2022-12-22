/* Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

public class QtiSupplicantIface {
    private static String mIfaceName = null;

    public void setIfaceName(String iface) {
        mIfaceName = iface;
    }

    public String getIfaceName() {
        return mIfaceName;
    }
}
