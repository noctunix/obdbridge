import 'package:flutter/material.dart';
import 'package:obdbridge/obdbridge.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('obdbridge example')),
        body: const ScanAndConnectScreen(),
      ),
    );
  }
}

/// Minimal screen exercising the plugin's scan/connect surface — no vehicle
/// command is ever issued here, this is a transport smoke test, not an OBD
/// client.
class ScanAndConnectScreen extends StatefulWidget {
  const ScanAndConnectScreen({super.key});

  @override
  State<ScanAndConnectScreen> createState() => _ScanAndConnectScreenState();
}

class _ScanAndConnectScreenState extends State<ScanAndConnectScreen> {
  final _api = NativeObdApi();

  bool _configured = false;
  bool _scanning = false;
  String _status = 'idle';
  List<ObdAdapterMessage> _adapters = [];
  String? _connectedHandle;

  @override
  void initState() {
    super.initState();
    _configure();
  }

  Future<void> _configure() async {
    // Required before any other call — see ObdBridgeConfigMessage's doc
    // comment. The example app has no recording UI, so these values are
    // never actually read in practice; they still have to be real-looking
    // so a later startRecording call (not exercised here) would not fail.
    await _api.configure(
      ObdBridgeConfigMessage(
        activityClassName: 'de.noctunix.obdbridge_example.MainActivity',
        smallIconResourceName: 'ic_launcher',
        sharedPreferencesName: 'obdbridge_example_recording_restart',
        bufferFormatId: 'obdbridge-example-buffer',
      ),
    );
    setState(() => _configured = true);
  }

  Future<void> _scan() async {
    setState(() {
      _scanning = true;
      _status = 'scanning…';
      _adapters = [];
    });
    final adapters = await _api.scanAdapters(10000);
    setState(() {
      _scanning = false;
      _adapters = adapters;
      _status = adapters.isEmpty ? 'no adapter found' : '${adapters.length} adapter(s) found';
    });
  }

  Future<void> _connect(ObdAdapterMessage adapter) async {
    setState(() => _status = 'connecting to ${adapter.handle}…');
    final state = await _api.connect(adapter.handle);
    setState(() {
      _status = 'link state: ${state.state.name}';
      _connectedHandle = state.state == ObdLinkStateValueMessage.connected ? adapter.handle : null;
    });
  }

  Future<void> _disconnect() async {
    await _api.disconnect();
    setState(() {
      _connectedHandle = null;
      _status = 'disconnected';
    });
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('obdbridge version: $obdbridgeVersion'),
          const SizedBox(height: 8),
          Text('status: $_status'),
          const SizedBox(height: 16),
          ElevatedButton(
            onPressed: !_configured || _scanning ? null : _scan,
            child: Text(_scanning ? 'Scanning…' : 'Scan for adapters'),
          ),
          const SizedBox(height: 8),
          if (_connectedHandle != null)
            ElevatedButton(onPressed: _disconnect, child: const Text('Disconnect')),
          const SizedBox(height: 16),
          Expanded(
            child: ListView.builder(
              itemCount: _adapters.length,
              itemBuilder: (context, index) {
                final adapter = _adapters[index];
                return ListTile(
                  title: Text(adapter.advertisedName ?? adapter.handle),
                  subtitle: Text('handle: ${adapter.handle}, rssi: ${adapter.rssi ?? '?'}'),
                  trailing: _connectedHandle == adapter.handle
                      ? const Icon(Icons.bluetooth_connected)
                      : const Icon(Icons.bluetooth),
                  onTap: _connectedHandle == null ? () => _connect(adapter) : null,
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
