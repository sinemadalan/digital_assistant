import 'dart:async';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

final storage = FlutterSecureStorage();
const AUTHKEY = "JWT-Server-Auth-Token";

Future<String?> getToken() async {
  return await storage.read(key: AUTHKEY);
}

Future<bool> checkIfTokenExists() async {
  return await storage.containsKey(key: AUTHKEY);
}

Future<bool> writeTokenIfNotExist(String val) async{
  if (await checkIfTokenExists() == true) {
    return false;
  }
  await storage.write(key: AUTHKEY, value: val);
  return true;
}

void forceDeleteToken(bool areYouSure) async {
  if (areYouSure == true){
    await storage.delete(key: AUTHKEY);
  }
}

Future<bool> mockEnrollDeviceIfTokenNotExist(String enrollment_code, String device_name) async {
  if (await checkIfTokenExists()) {
    return false;
  }
  forceDeleteToken(true);
  try {
    var url = Uri
        .https(
        'example.com',
        'whatsit/create');
    var response = await http
        .post(
        url,
        body: {
          'name': 'doodle',
          'color': 'blue'
        });
    print(
        'Response status: ${response
            .statusCode}');
    print(
        'Response body: ${response
            .body}');
    print(
        await http
            .read(
            Uri
                .https(
                'example.com',
                'foobar.txt')));
  } catch (e){

  }
  final String token = "mockJWTToken1234";
  writeTokenIfNotExist(token);
  return true;

}