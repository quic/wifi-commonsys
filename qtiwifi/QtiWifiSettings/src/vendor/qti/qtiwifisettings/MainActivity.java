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

package vendor.qti.qtiwifisettings;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.EditText;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Build;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.util.List;

import android.net.wifi.WifiManager;
import com.qualcomm.qti.qtiwifi.QtiWifiManager;
import com.qualcomm.qti.qtiwifi.ThermalData;

public class MainActivity extends Activity implements View.OnClickListener {

    private static final String TAG = "QtiWifiSettingsApp";
    private Intent mServiceIntent;
    //button objects
    private Button buttonStart;
    private Button buttonStop;
    private Button buttonCommand;
    private EditText editTextCommand;
    private TextView textViewCommand;
    private TextView eventViewCommand;
    private WifiManager mWifiManager;
    private static QtiWifiManager mUniqueInstance = null;
    private FileOutputStream fileout;
    private OutputStreamWriter outputWriter;

    private static final String COMMAND_GET_AVAILABLE_INTERFACES = "list-interfaces";
    private static final String COMMAND_GET_THERMAL_INFO = "get-thermal-info";
    private static final String COMMAND_REGISTER_VENDOR_EVENT_CALLBACK =
                                                 "register-vendor-event-callback";
    private static final String COMMAND_UNREGISTER_VENDOR_EVENT_CALLBACK =
                                                 "unregister-vendor-event-callback";
    private static final String COMMAND_SET_TXPOWER = "set-txpower";
    private static final String COMMAND_RESULT_FAILED = "FAILED";
    private static final String COMMAND_RESULT_SUCCESS = "SUCCESS";
    private static final String COMMAND_RESULT_INVALID_COMMAND = "Invalid command!";
    private static final String COMMAND_RESULT_INVALID_ARGS = "Invalid args!";

    private QtiWifiManager.CsiCallback mCsiCallback = new QtiWifiManager.CsiCallback() {
        @Override
        public void onCsiUpdate(byte[] info) {
            StringBuilder strBuilder = new StringBuilder();
            for(byte val : info) {
                strBuilder.append(String.format("%02x", val&0xff));
            }
            Log.d(TAG, "onCsiUpdate csi info = " + info);
            try {
                outputWriter.write(strBuilder.toString());
                Log.i(TAG, "Successfully write into the file");
            } catch (IOException e) {
                Log.e(TAG, "Error while writing into the file");
            }
        }
    };

    private QtiWifiManager.VendorEventCallback mVendorEventCallback =
        new QtiWifiManager.VendorEventCallback() {
        @Override
        public void onThermalChanged(String ifname, int level) {
            //ignore ifname as we don't care
            Log.i(TAG, "onThermalChanged, level = " + level);
            eventViewCommand.setText("Received thermal change event: level=" + level);
        }
    };

    private QtiWifiManager.ApplicationBinderCallback mApplicationCallback = new QtiWifiManager.ApplicationBinderCallback() {
        @Override
        public void onAvailable(QtiWifiManager qtiWifiManager) {
            Log.d(TAG, "onAvailable called");
            mUniqueInstance = qtiWifiManager;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        QtiWifiManager.initialize(this, mApplicationCallback);
        Log.i(TAG, "initialize called");

        setContentView(R.layout.content_main);
        //getting buttons from xml
        buttonStart = (Button) findViewById(R.id.buttonStart);
        buttonStop = (Button) findViewById(R.id.buttonStop);

        //attaching onclicklistener to buttons
        buttonStart.setOnClickListener(this);
        buttonStop.setOnClickListener(this);

        buttonCommand = (Button) findViewById(R.id.buttonCommand);
        editTextCommand = (EditText) findViewById(R.id.editTextCommand);
        textViewCommand = (TextView) findViewById(R.id.textViewCommand);
        eventViewCommand = (TextView) findViewById(R.id.eventViewCommand);
        buttonCommand.setOnClickListener(this);
        textViewCommand.setMovementMethod(ScrollingMovementMethod.getInstance());
        eventViewCommand.setMovementMethod(ScrollingMovementMethod.getInstance());

        ViewGroup layout = (ViewGroup) buttonCommand.getParent();
        if (!isAutoPlatform()) {
            layout.removeView(buttonCommand);
            layout.removeView(editTextCommand);
            layout.removeView(textViewCommand);
            layout.removeView(eventViewCommand);
        } else {
            layout.removeView(buttonStart);
            layout.removeView(buttonStop);
        }

        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
    }

    private void showMessage(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onClick(View view) {
        if (mUniqueInstance == null) {
            showMessage("uniqueInstance is null");
            Log.e(TAG, "Failed to get QtiWifiManager instance");
            return;
        }
        if (view == buttonStart) {
            //starting service
            if (!mWifiManager.isWifiEnabled()) {
                showMessage("Turn on Wifi before capturing CSI data");
                Log.e(TAG, "Turn on Wifi before capturing CSI data");
            } else if (mWifiManager.isWifiEnabled()) {
                showMessage("CSI start until user stops");
                mUniqueInstance.startCsi(mCsiCallback, null);
                try {
                    fileout=openFileOutput("mytextfile.txt", MODE_PRIVATE);
                    outputWriter=new OutputStreamWriter(fileout);
                    showMessage("File open successfully!");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open file");
                }
            } else {
                showMessage("uniqueInstance is null");
                Log.e(TAG, "Failed to get QtiWifiManager instance");
            }
        } else if (view == buttonStop) {
            //stopping service
            mUniqueInstance.stopCsi(mCsiCallback);
            try {
                outputWriter.close();
                Log.i(TAG, "Succseefully close the file");
            } catch (IOException e) {
                Log.e(TAG, "Failed to close the file");
            }
        } else if (view == buttonCommand) {
            String command = editTextCommand.getText().toString();
            String reply = "";
            String[] params = command.split("\\s+");
            int size = params.length;
            if (size == 0) {
                reply = COMMAND_RESULT_INVALID_COMMAND;
            } else if (params[0].equals(COMMAND_GET_AVAILABLE_INTERFACES)) {
                List<String> ifaces = mUniqueInstance.getAvailableInterfaces();
                if (ifaces != null) {
                    reply = ifaces.toString();
                }
            } else if (params[0].equals(COMMAND_GET_THERMAL_INFO)) {
                reply = getThermalInfo(params);
            } else if (params[0].equals(COMMAND_REGISTER_VENDOR_EVENT_CALLBACK)) {
                mUniqueInstance.registerVendorEventCallback(mVendorEventCallback, null);
                reply = "OK";
            } else if (params[0].equals(COMMAND_UNREGISTER_VENDOR_EVENT_CALLBACK)) {
                mUniqueInstance.unregisterVendorEventCallback(mVendorEventCallback);
                reply = "OK";
            } else if (params[0].equals(COMMAND_SET_TXPOWER)) {
                reply = setTxPower(params);
            } else {
                reply = COMMAND_RESULT_INVALID_COMMAND;
            }
            reply = "result of " + command + ":\n" + reply;
            textViewCommand.setText(reply);
        }
    }

    private boolean isAutoPlatform() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    private String getThermalInfo(String[] params) {
        if (params.length < 2) {
            return COMMAND_RESULT_INVALID_ARGS;
        }
        ThermalData data = mUniqueInstance.getThermalInfo(params[1]);
        if (data == null) {
            return COMMAND_RESULT_FAILED;
        }
        String reply = "temperature=" + data.getTemperature()
                     + " level="+ data.getThermalLevel();
        return reply;
    }

    private String setTxPower(String[] params) {
        if (params.length < 3) {
            return COMMAND_RESULT_INVALID_ARGS;
        }

        try {
            String ifname = params[1];
            int dbm = Integer.parseInt(params[2]);
            boolean res = mUniqueInstance.setTxPower(ifname, dbm);
            if (!res) {
                return COMMAND_RESULT_FAILED;
            }
        } catch (NumberFormatException e) {
            return COMMAND_RESULT_INVALID_ARGS;
        }

        return COMMAND_RESULT_SUCCESS;
    }

    public static void unbindService(Context context) {
        QtiWifiManager.unbindService(context);
    }

    @Override
    protected void onDestroy() {
        Log.i("MAINACT", "onDestroy!");
        super.onDestroy();
        try {
            unbindService(getApplicationContext());
        } catch (IllegalArgumentException e) {
            Log.e(TAG,"Illegal Argument Exception ",e);
        }
    }

    private void quitApplicationWithAlert(int title, int message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title)
                .setMessage(message)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setCancelable(false)
                .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which)  {
                        // Exit the application.
                        finish();
                    }
                });
        AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(false);
        alert.show();
    }
}
