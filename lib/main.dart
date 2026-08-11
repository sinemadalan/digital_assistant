import 'package:flutter/material.dart';
import 'accessibility_service_manager.dart';

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

class ControlPanel extends StatefulWidget {
  const ControlPanel({Key? key}) : super(key: key);

  @override
  _ControlPanelState createState() => _ControlPanelState();
}

class _ControlPanelState extends State<ControlPanel> {
  bool _isPaused = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Accessibility Control')),
      // The StreamBuilder is now at the top level of the body
      // It passes the state down to a clean, declarative layout function
      body: StreamBuilder<bool>(
        stream: AccessibilityManager.serviceStatusStream,
        initialData: false,
        builder: (context, snapshot) {
          final isRunning = snapshot.data ?? false;
          return _buildMainLayout(isRunning);
        },
      ),
    );
  }

  /// Organizes the main vertical structure of the screen.
  Widget _buildMainLayout(bool isRunning) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          _buildStatusIndicator(isRunning),
          const SizedBox(height: 30),
          // Conditionally show either the settings button OR the pause toggle
          if (!isRunning) _buildSettingsButton() else _buildPauseToggle(),
        ],
      ),
    );
  }

  /// Visual feedback for the current system permission state.
  Widget _buildStatusIndicator(bool isRunning) {
    return Column(
      children: [
        Icon(
          isRunning ? Icons.check_circle : Icons.warning_amber_rounded,
          color: isRunning ? Colors.green : Colors.red,
          size: 64,
        ),
        const SizedBox(height: 12),
        Text(
          isRunning ? 'Service is ENABLED' : 'Service is DISABLED',
          style: TextStyle(
            fontSize: 22,
            fontWeight: FontWeight.bold,
            color: isRunning ? Colors.green : Colors.red,
          ),
        ),
      ],
    );
  }

  /// Button to route the user to Android Settings.
  Widget _buildSettingsButton() {
    return ElevatedButton.icon(
      onPressed: AccessibilityManager.openSettings,
      icon: const Icon(Icons.settings),
      label: const Text('Grant Permission in Settings'),
      style: ElevatedButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
      ),
    );
  }

  /// The pause toggle, isolated in its own widget block.
  Widget _buildPauseToggle() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16.0),
      child: Card(
        child: ListTile(
          title: const Text('Pause Service (Save CPU)'),
          subtitle: const Text('Stops listening to events without revoking permission'),
          trailing: Switch(
            value: _isPaused,
            onChanged: _handlePauseToggle,
          ),
        ),
      ),
    );
  }

  /// Handles the state update and native communication for pausing.
  void _handlePauseToggle(bool value) {
    setState(() {
      _isPaused = value;
    });
    AccessibilityManager.setPaused(value);
  }
}