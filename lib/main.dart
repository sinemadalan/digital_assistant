import 'package:accessibility_service/pages/control_panel.dart';
import 'package:accessibility_service/pages/login_page.dart';
import 'package:flutter/material.dart';
import 'Util/user_auth_manager.dart' as auth;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final is_authenticated = await auth.checkIfTokenExists();
  runApp(MyApp(is_authenticated));
}

class MyApp extends StatelessWidget {
  bool user_is_authenticated = false;
  MyApp(bool isUserAuthenticated, {Key? key}) : super(key: key){
    user_is_authenticated=isUserAuthenticated;
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Accessibility Service Panel',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      // This is where you inject your Scaffold
      home: user_is_authenticated ? ControlPanel() : LoginPage(),
    );
  }
}
