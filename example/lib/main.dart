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
        body: const Center(child: Text('obdbridge version: $obdbridgeVersion')),
      ),
    );
  }
}
