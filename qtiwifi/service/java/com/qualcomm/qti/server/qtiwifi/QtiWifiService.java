/* Copyright (c) 2021-2022 Qualcomm Innovation Center, Inc.
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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import com.qualcomm.qti.qtiwifi.IQtiWifiManager;

public final class QtiWifiService extends Service {
    private static final String TAG = "QtiWifiService";
    private static final boolean DBG = true;
    private IQtiWifiManager.Stub mBinder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand()");

        Notification.Builder nb = new Notification.Builder(
                getApplicationContext())
            .setContentTitle(getResources().getString(R.string.notif_title))
            .setContentText(getResources().getString(R.string.notif_text))
            .setSmallIcon(R.mipmap.icon);
            nb.setChannelId(createNotificationChannel(TAG,
                    getResources().getString(R.string.channel_name))
                    .getId());
        startForeground(Process.myPid(), nb.build());
        return super.onStartCommand(intent, flags, startId);
    }

    private NotificationChannel createNotificationChannel(
            String channelId, String channelName){
        Log.d(TAG, "createNotificationChannel called");
        NotificationChannel channel = new NotificationChannel(channelId,
                channelName, NotificationManager.IMPORTANCE_NONE);
        NotificationManager service = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        service.createNotificationChannel(channel);
        return channel;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind()");
        if (mBinder == null) {
            Context c = getApplicationContext();
            Log.d(TAG, "Creating QtiWifiServiceImpl with context:" + c);
            mBinder = new QtiWifiServiceImpl(c);
        }
        return mBinder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");
        super.onDestroy();
        if (mBinder != null && mBinder instanceof QtiWifiServiceImpl) {
            Log.d(TAG, "Unregister callbacks");
            ((QtiWifiServiceImpl) mBinder).destroyService();
            mBinder = null;
        }
    }
}
