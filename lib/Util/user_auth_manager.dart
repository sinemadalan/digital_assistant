import 'dart:async';
import 'dart:convert';
import 'dart:developer' as developer;

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import './accessibility_service_manager.dart';
import './EnrollResult.dart';

final storage = FlutterSecureStorage();
const AUTHKEY = "JWT-Server-Auth-Token";

Future<String?> getToken() async {
  return await storage.read(key: AUTHKEY);
}

Future<bool> checkIfTokenExists() async {
  return await storage.containsKey(key: AUTHKEY);
}

Future<bool> writeTokenIfNotExist(String val) async {
  if (await checkIfTokenExists() == true) {
    return false;
  }
  await writeToken(val);
  return true;
}

Future<void> writeToken(String val) async {
  if (val.trim().isEmpty) {
    throw ArgumentError('Token must not be blank');
  }
  await storage.write(key: AUTHKEY, value: val);
  await syncTokenToNative(val);
}

Future<void> forceDeleteToken(bool areYouSure) async {
  if (areYouSure == true) {
    await AccessibilityManager.clearAuthToken();
    await storage.delete(key: AUTHKEY);
  }
}

Future<bool> syncTokenToNative(String token) async {
  if (token.trim().isEmpty) {
    return false;
  }

  try {
    await AccessibilityManager.setAuthToken(token);
    return true;
  } catch (e) {
    developer.log(
      'Failed to synchronize the authentication token to Android.',
      name: 'user_auth_manager',
      error: e,
    );
    return false;
  }
}

Future<EnrollResult> mockEnrollDeviceIfTokenNotExist(String enrollmentCode, String deviceName) async {
  try {
    if (await checkIfTokenExists()) {
      return EnrollResult.tokenAlreadyExists;
    }
    await forceDeleteToken(true);

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
