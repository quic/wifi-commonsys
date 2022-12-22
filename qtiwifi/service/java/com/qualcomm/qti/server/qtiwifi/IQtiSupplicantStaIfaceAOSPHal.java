/* Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qti.server.qtiwifi;

import android.annotation.NonNull;

/** Abstraction of Qti Supplicant STA Iface AOSP HAL interface */
interface IQtiSupplicantStaIfaceAOSPHal {
    /**
     * Begin initializing the IQtiSupplicantStaIfaceAOSPHal object. Specific initialization
     * logic differs between the HIDL and AIDL implementations.
     *
     * @return true if the initialization routine was successful
     */
    boolean initialize();

    /**
     * Setup a STA interface for the specified iface name.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    boolean setupIface(@NonNull String ifaceName);
}
