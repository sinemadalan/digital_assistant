import 'package:accessibility_service/Util/EnrollResult.dart';
import 'package:accessibility_service/Util/user_auth_manager.dart' as auth;
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

void main() {
  test('successful enrollment installs the fresh token exactly once', () async {
    var installCalls = 0;

    final result = await auth.mockEnrollDeviceIfTokenNotExist(
      'pairing-code',
      'device',
      tokenExists: () async => false,
      deleteExistingToken: (_) async {},
      postEnrollment: (_, {headers, body, encoding}) async =>
          http.Response('{"token":"fresh-token"}', 201),
      installEnrollmentToken: (token) async {
        expect(token, 'fresh-token');
        installCalls += 1;
      },
    );

    expect(result, EnrollResult.success);
    expect(installCalls, 1);
  });

  test('failed enrollment never installs a fresh token', () async {
    var installCalls = 0;

    final result = await auth.mockEnrollDeviceIfTokenNotExist(
      'pairing-code',
      'device',
      tokenExists: () async => false,
      deleteExistingToken: (_) async {},
      postEnrollment: (_, {headers, body, encoding}) async =>
          http.Response('', 400),
      installEnrollmentToken: (_) async => installCalls += 1,
    );

    expect(result, EnrollResult.invalidCode);
    expect(installCalls, 0);
  });
}
