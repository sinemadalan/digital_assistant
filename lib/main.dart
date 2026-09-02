import 'pages/control_panel.dart';
import 'pages/login_page.dart';
import 'package:flutter/material.dart';
import 'Util/user_auth_manager.dart' as auth;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final isAuthenticated = await auth.synchronizeAuthStateOnStartup();
  runApp(MyApp(isAuthenticated));
}

class MyApp extends StatelessWidget {
  final bool isAuthenticated;

  const MyApp(this.isAuthenticated, {super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Accessibility Service Panel',
      theme: ThemeData(primarySwatch: Colors.blue),
      // This is where you inject your Scaffold
      home: isAuthenticated ? const ControlPanel() : const LoginPage(),
    );
  }
}
