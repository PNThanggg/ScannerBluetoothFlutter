import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'scanner_bluetooth.dart';

void main() => runApp(const MyApp());

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String _data = '';
  bool _scanning = false;
  final FlutterScanBluetooth _bluetooth = FlutterScanBluetooth();

  @override
  void initState() {
    super.initState();

    _bluetooth.devices.listen((device) {
      setState(() {
        _data += '${device.name} (${device.address})\n';
      });
    });

    _bluetooth.scanStopped.listen((device) {
      setState(() {
        _scanning = false;
        _data += 'scan stopped\n';
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: Column(
          mainAxisAlignment: MainAxisAlignment.start,
          children: <Widget>[
            Expanded(child: Text(_data)),
            Padding(
              padding: const EdgeInsets.all(8.0),
              child: Center(
                child: ElevatedButton(
                    child: Text(_scanning ? 'Stop scan' : 'Start scan'),
                    onPressed: () async {
                      try {
                        if (_scanning) {
                          await _bluetooth.stopScan();
                          debugPrint("scanning stoped");
                          setState(() {
                            _data = '';
                          });
                        } else {
                          await _bluetooth.startScan(pairedDevices: false);
                          debugPrint("scanning started");
                          setState(() {
                            _scanning = true;
                          });
                        }
                      } on PlatformException catch (e) {
                        debugPrint(e.toString());
                      }
                    }),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(8.0),
              child: Center(
                child: TextButton(
                    child: const Text('Check permissions'),
                    onPressed: () async {
                      try {
                        await _bluetooth.requestPermissions();
                        debugPrint('All good with perms');
                      } on PlatformException catch (e) {
                        debugPrint(e.toString());
                      }
                    }),
              ),
            )
          ],
        ),
      ),
    );
  }
}
