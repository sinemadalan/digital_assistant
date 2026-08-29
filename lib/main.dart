import 'package:accessibility_service/pages/control_panel.dart';
import 'package:flutter/material.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Accessibility Service Panel',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      // This is where you inject your Scaffold
      home: const ControlPanel(),
    );
  }
}
