/*
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.qtiwifi;

/**
 * Interface for vendor event callback (thermal change or congestion report).
 *
 */

oneway interface IVendorEventCallback
{
    void onThermalChanged(String ifname, int level);
}
