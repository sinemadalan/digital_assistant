import 'package:accessibility_service/Util/EnrollResult.dart';
import 'package:accessibility_service/pages/control_panel.dart';
import 'package:flutter/material.dart';
import '../Util/user_auth_manager.dart' as auth;

class LoginPage extends StatefulWidget {
  const LoginPage({super.key});

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final TextEditingController _pairingCodeController = TextEditingController();
  final TextEditingController _deviceNameController = TextEditingController();
  final TextEditingController _debugTokenController = TextEditingController(
    text:
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIwMWEwNTgyMS02NTg4LTdlNDQtODg4OC1mMDNlYmEwOTEyY2MiLCJ0eXAiOiJkZXZpY2UiLCJpYXQiOjE3ODgxODQ5Nzh9.x_90gl0Tc9jqkoygWj5nB2MX9RlXvuoBsU3diMo-9Ug",
  );

  // 3. Dispose of controllers when the widget is destroyed
  @override
  void dispose() {
    _pairingCodeController.dispose();
    _deviceNameController.dispose();
    _debugTokenController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    Future<void> navigateToControlPanel() => Navigator.push(
      context,
      MaterialPageRoute<void>(builder: (context) => const ControlPanel()),
    );

    return Scaffold(
      appBar: AppBar(
        title: const Text('Login'),
        actions: [
          TextButton.icon(
            label: const Text("Go to control panel"),
            icon: const Icon(Icons.navigate_next),
            onPressed: navigateToControlPanel,
          ),
        ],
      ),
      body: Center(
        child: SingleChildScrollView(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              decoratedTextFieldContainer("Pairing Code", _pairingCodeController),
              decoratedTextFieldContainer("Device Name", _deviceNameController),
              const SizedBox(height: 12),
              decoratedButtonContainer("Go", () async {
                String key = _pairingCodeController.text;
                String deviceName = _deviceNameController.text;
                var result = await auth.mockEnrollDeviceIfTokenNotExist(key, deviceName);
                if (result == EnrollResult.success) {
                  navigateToControlPanel();
                } else {
                  print("Error: $result");
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Error: $result")));
                  }
                }
              }),
              const SizedBox(height: 24),
              const Divider(indent: 20, endIndent: 20),
              const SizedBox(height: 12),
              decoratedTextFieldContainer("Debug JWT Key", _debugTokenController),
              const SizedBox(height: 12),
              decoratedButtonContainer(
                "Skip Enrollment with Key",
                () async {
                  String token = _debugTokenController.text;
                  String deviceName =
                      _deviceNameController.text.isNotEmpty
                          ? _deviceNameController.text
                          : "idk";
                  var result = await auth.skipEnrollWithKeyForDebug(token, deviceName);
                  if (result == EnrollResult.success) {
                    navigateToControlPanel();
                  } else {
                    print("Error: $result");
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Error: $result")));
                    }
                  }
                },
                icon: Icons.bug_report,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Container decoratedTextFieldContainer(String str, TextEditingController controller){
    return Container(
      margin: EdgeInsets.only(top: 20,left: 20,right: 20),
      decoration: BoxDecoration(
        boxShadow: [
          BoxShadow(
              color: Color(0xff1d1617).withValues(alpha: 0.11),
              blurRadius: 40,
              spreadRadius: 0.0
          ),
        ],
      ),
      child: TextField(
        controller: controller,
        decoration: InputDecoration(
            hintText: str,
            filled:true,
            fillColor: Colors.white,
            contentPadding: EdgeInsets.all(15),
            border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(15),
                borderSide: BorderSide.none
            )
        ),
      ),

    );
  }

  Widget decoratedButtonContainer(
    String str,
    void Function()? callback, {
    IconData icon = Icons.send,
  }) {
    return ElevatedButton.icon(
      onPressed: callback,
      icon: Icon(icon),
      label: Text(str),
      style: ElevatedButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
      ),
    );
  }

}


