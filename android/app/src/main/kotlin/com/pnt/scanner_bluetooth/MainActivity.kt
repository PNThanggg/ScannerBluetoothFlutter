package com.pnt.scanner_bluetooth

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    companion object {
        private const val TAG = "Scanner Bluetooth"
        private const val REQUEST_BLUETOOTH = 7338
        private const val REQUEST_PERMISSION = 242346
        private const val ACTION_NEW_DEVICE = "action_new_device"
        private const val ACTION_START_SCAN = "action_start_scan"
        private const val ACTION_STOP_SCAN = "action_stop_scan"
        private const val ACTION_SCAN_STOPPED = "action_scan_stopped"
        private const val ACTION_REQUEST_PERMISSIONS = "action_request_permissions"
    }

    private var methodChannel: MethodChannel? = null

    private var bluetoothAdapter: BluetoothAdapter? = null

    private var onPermissionGranted: (() -> Unit)? = null
    private var onPermissionRefused: ((code: String, message: String) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothDevice.ACTION_FOUND == intent.action) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                if (device != null) {
                    methodChannel?.invokeMethod(ACTION_NEW_DEVICE, toMap(device))
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == intent.action) {
                methodChannel?.invokeMethod(ACTION_SCAN_STOPPED, null)
            }
        }
    }

    @SuppressLint("ObsoleteSdkInt", "MissingPermission")
    private fun toMap(device: BluetoothDevice): Map<String, String> {
        val map = HashMap<String, String>()
        var name = device.name ?: "No name"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && !name.contains("-LE")) {
            name += if (device.type == DEVICE_TYPE_LE) "-LE" else ""
        }

        map["name"] = name
        map["address"] = device.address

        return map
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "scan_bluetooth",
        )

        bluetoothAdapter =
            (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

//        bluetoothAdapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
//            (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
//        } else {
//            BluetoothAdapter.getDefaultAdapter()
//        }

        methodChannel!!.setMethodCallHandler { call, result ->
            if (bluetoothAdapter == null) {
                result.error(
                    "error_no_bt",
                    "Bluetooth adapter is null, BT is not supported on this device",
                    null
                )
            }

            when (call.method) {
                ACTION_START_SCAN -> scan(result, call.arguments as Boolean)
                ACTION_STOP_SCAN -> stopScan(result)
                ACTION_REQUEST_PERMISSIONS -> validatePermissions(result)
                else -> result.notImplemented()
            }
        }
    }

    private fun validatePermissions(result: MethodChannel.Result) {
        startPermissionValidation({
            result.success(null)
        }, { code: String, message: String ->
            result.error(code, message, null)
            onPermissionGranted = null
            onPermissionRefused = null
        })
    }

    private fun startPermissionValidation(
        onGranted: (() -> Unit),
        onRefused: ((code: String, message: String) -> Unit)
    ) {

        var isPermsOk =
            activity.checkCallingOrSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
                    && activity.checkCallingOrSelfPermission(BLUETOOTH_ADMIN) == PERMISSION_GRANTED
                    && activity.checkCallingOrSelfPermission(BLUETOOTH) == PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isPermsOk = activity.checkCallingOrSelfPermission(BLUETOOTH) == PERMISSION_GRANTED
                    && activity.checkCallingOrSelfPermission(BLUETOOTH_ADMIN) == PERMISSION_GRANTED
                    && activity.checkCallingOrSelfPermission(BLUETOOTH_SCAN) == PERMISSION_GRANTED
                    && activity.checkCallingOrSelfPermission(BLUETOOTH_CONNECT) == PERMISSION_GRANTED
        }

        if (isPermsOk) {
            if (bluetoothAdapter!!.isEnabled) {
                onPermissionGranted = onGranted
                onPermissionRefused = onRefused
                GpsUtils(activity).turnGPSOn {
                    if (it) {
                        onGranted()
                    } else {
                        onRefused("error_no_gps", "Gps need to be turned on to scan BT devices")
                    }
                    onPermissionGranted = null
                    onPermissionRefused = null
                }
            } else {
                onPermissionGranted = onGranted
                onPermissionRefused = onRefused
                val enableBT = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                activity.startActivityForResult(enableBT, REQUEST_BLUETOOTH)
            }
        } else {
            onPermissionGranted = onGranted
            onPermissionRefused = onRefused
            var perms = arrayOf(ACCESS_FINE_LOCATION, BLUETOOTH, BLUETOOTH_ADMIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms = arrayOf(BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
            }

            ActivityCompat.requestPermissions(activity, perms, REQUEST_PERMISSION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(result: MethodChannel.Result?) {
        bluetoothAdapter?.cancelDiscovery()

        methodChannel!!.invokeMethod(ACTION_SCAN_STOPPED, null)

        try {
            activity.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, e)
        }

        result?.success(null)
    }

    @SuppressLint("MissingPermission")
    private fun scan(result: MethodChannel.Result, returnBondedDevices: Boolean = false) {

        startPermissionValidation({
            if (bluetoothAdapter!!.isDiscovering) {
                // Bluetooth is already in modo discovery mode, we cancel to restart it again
                stopScan(null)
            }

            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            activity.registerReceiver(receiver, filter)

            bluetoothAdapter!!.startDiscovery()

            var bondedDevices: List<Map<String, String>> = arrayListOf()
            if (returnBondedDevices) {
                bondedDevices = bluetoothAdapter!!.bondedDevices.mapNotNull { device ->
                    toMap(device)
                }
            }
            result.success(bondedDevices)

        }, { code: String, message: String ->
            result.error(code, message, null)
        })
    }

    override fun onDestroy() {
        super.onDestroy()

        methodChannel!!.setMethodCallHandler(null)
        methodChannel = null
    }
}
