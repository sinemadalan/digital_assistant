import 'dart:async';
import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import './EnrollResult.dart';
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

Future<EnrollResult> mockEnrollDeviceIfTokenNotExist(String enrollmentCode, String deviceName) async {
  try {
    if (await checkIfTokenExists()) {
      return EnrollResult.tokenAlreadyExists;
    }
    forceDeleteToken(true);

    final url = Uri.parse('https://api.152-70-40-87.nip.io/v1/enroll');

    final response = await http.post(
      url,
      headers: {
        'Content-Type': 'application/json',
      },
      body: jsonEncode({
        'code': enrollmentCode,
        'device_name': deviceName,
      }),
    );

    if (response.statusCode == 200 || response.statusCode == 201) {
      final Map<String, dynamic> responseData = jsonDecode(response.body);

      final String token = responseData['token'];

      await writeTokenIfNotExist(token);

      return EnrollResult.success;
    }
    else if (response.statusCode == 400) {
      return EnrollResult.invalidCode;
    }
    else if (response.statusCode == 409) {
      return EnrollResult.duplicateEnrollment;
    }
    else {
      return EnrollResult.serverError;
    }

  } catch (e) {
    print('Network or parsing error during enrollment: $e');
    return EnrollResult.networkError;
  }
}
