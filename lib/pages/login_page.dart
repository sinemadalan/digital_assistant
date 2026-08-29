import 'package:flutter/material.dart';
class LoginPage extends StatelessWidget{
  const LoginPage({Key? key}) : super(key: key);
  @override
  Widget build(BuildContext context) {

    return Scaffold(
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            decoratedTextFieldContainer("Pairing Code"),
            decoratedTextFieldContainer("Device Name"),
            decoratedButtonContainer("Go", () {print("Pressed GO button");})
          ],
        ),
      ),
    );
  }

  Container decoratedTextFieldContainer(String str){
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


