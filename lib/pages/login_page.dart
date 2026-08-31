import 'package:accessibility_service/pages/control_panel.dart';
import 'package:flutter/material.dart';
import '../Util/user_auth_manager.dart' as auth;

class LoginPage extends StatefulWidget {
  const LoginPage({Key? key}) : super(key: key);

  @override
  State<LoginPage> createState() => _LoginPageState();
}
class _LoginPageState extends State<LoginPage>{
  @override
  State<LoginPage> createState() => _LoginPageState();
  final TextEditingController _pairingCodeController = TextEditingController();
  final TextEditingController _deviceNameController = TextEditingController();

  // 3. Dispose of controllers when the widget is destroyed
  @override
  void dispose() {
    _pairingCodeController.dispose();
    _deviceNameController.dispose();
    super.dispose();
  }

  Widget build(BuildContext context) {
    Future<void> navigateToControlPanel () => Navigator.push(
        context,
        MaterialPageRoute<void>(builder: (context) => const ControlPanel())
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
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            decoratedTextFieldContainer("Pairing Code", _pairingCodeController),
            decoratedTextFieldContainer("Device Name", _deviceNameController),
            decoratedButtonContainer("Go", () async {
                String key = _pairingCodeController.text;
                String deviceName = _deviceNameController.text;

                if (await auth.mockEnrollDeviceIfTokenNotExist(key, deviceName)) {
                  navigateToControlPanel();
                }else{
                  print("AW*RHAEUOGNONEGNNIOAWFHOIHAWIR");
                }
              }

            )
          ],
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

  Widget decoratedButtonContainer(String str, void Function()? callback) {
    return ElevatedButton.icon(
      onPressed: callback,
      icon: const Icon(Icons.send),
      label: Text(str),
      style: ElevatedButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
      ),
    );
  }

}


