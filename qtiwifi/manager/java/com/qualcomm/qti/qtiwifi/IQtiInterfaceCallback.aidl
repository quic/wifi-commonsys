/* Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.qtiwifi;

/**
 * Interface for QtiInterface callback.
 *
 */

oneway interface IQtiInterfaceCallback
{
    void onNetworkDisconnect(in byte[] bssid, int reasonCode, boolean locallyGenerated);
    void onWifiAlert(int errorCode, in byte[] address);
}
