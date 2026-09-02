import 'dart:async';
import 'dart:convert';
import 'dart:developer' as developer;

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import './accessibility_service_manager.dart';
import './EnrollResult.dart';

final storage = FlutterSecureStorage();
const AUTHKEY = "JWT-Server-Auth-Token";

typedef EnrollmentPost =
    Future<http.Response> Function(
      Uri url, {
      Map<String, String>? headers,
      Object? body,
      Encoding? encoding,
    });

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

Future<void> deleteTokenFromSecureStorage() async {
  await storage.delete(key: AUTHKEY);
}

Future<void> forceDeleteToken(bool areYouSure) async {
  if (areYouSure == true) {
    await AccessibilityManager.clearAuthToken();
    await deleteTokenFromSecureStorage();
  }
}

Future<bool> syncTokenToNative(String token) async {
  if (token.trim().isEmpty) {
    return false;
  }

  try {
    return await AccessibilityManager.setAuthToken(token);
  } catch (e) {
    developer.log(
      'Failed to synchronize the authentication token to Android.',
      name: 'user_auth_manager',
      error: e,
    );
    return false;
  }
}

Future<bool> synchronizeAuthStateOnStartup({
  Future<bool> Function()? readReauthenticationRequired,
  Future<String?> Function()? readSecureToken,
  Future<void> Function()? deleteSecureToken,
  Future<bool> Function(String token)? synchronizeNativeToken,
}) async {
  final readReauth =
      readReauthenticationRequired ??
      AccessibilityManager.isReauthenticationRequired;

  final bool reauthenticationRequired;
  try {
    reauthenticationRequired = await readReauth();
  } catch (e) {
    developer.log(
      'Unable to determine native authentication state; token sync skipped.',
      name: 'user_auth_manager',
      error: e,
    );
    return false;
  }

  if (reauthenticationRequired) {
    await (deleteSecureToken ?? deleteTokenFromSecureStorage)();
    return false;
  }

  final token = await (readSecureToken ?? getToken)();
  if (token == null || token.trim().isEmpty) {
    return false;
  }

  final bool accepted;
  try {
    accepted =
        await (synchronizeNativeToken ?? AccessibilityManager.setAuthToken)(
          token,
        );
  } catch (e) {
    developer.log(
      'Failed to synchronize the authentication token to Android.',
      name: 'user_auth_manager',
      error: e,
    );
    return false;
  }

  if (!accepted) {
    await (deleteSecureToken ?? deleteTokenFromSecureStorage)();
    return false;
  }

  return true;
}

Future<void> _installFreshEnrollmentToken(String token) async {
  if (token.trim().isEmpty) {
    throw ArgumentError('Token must not be blank');
  }
  await storage.write(key: AUTHKEY, value: token);
  await AccessibilityManager.installFreshAuthToken(token);
}

Future<EnrollResult> mockEnrollDeviceIfTokenNotExist(
  String enrollmentCode,
  String deviceName, {
  Future<bool> Function()? tokenExists,
  Future<void> Function(bool areYouSure)? deleteExistingToken,
  EnrollmentPost? postEnrollment,
  Future<void> Function(String token)? installEnrollmentToken,
}) async {
  try {
    if (await (tokenExists ?? checkIfTokenExists)()) {
      return EnrollResult.tokenAlreadyExists;
    }
    await (deleteExistingToken ?? forceDeleteToken)(true);

    final url = Uri.parse('https://api.152-70-40-87.nip.io/v1/enroll');

    final response = await (postEnrollment ?? http.post)(
      url,
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'code': enrollmentCode, 'device_name': deviceName}),
    );

    if (response.statusCode == 200 || response.statusCode == 201) {
      final Map<String, dynamic> responseData = jsonDecode(response.body);

      final String token = responseData['token'];

      await (installEnrollmentToken ?? _installFreshEnrollmentToken)(token);

      return EnrollResult.success;
    } else if (response.statusCode == 400) {
      return EnrollResult.invalidCode;
    } else if (response.statusCode == 409) {
      return EnrollResult.duplicateEnrollment;
    } else {
      return EnrollResult.serverError;
    }
  } catch (e) {
    print('Network or parsing error during enrollment: $e');
    return EnrollResult.networkError;
  }
}
