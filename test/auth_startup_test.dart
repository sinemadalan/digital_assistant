import 'package:accessibility_service/Util/user_auth_manager.dart' as auth;
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('valid native state synchronizes an existing secure token', () async {
    String? synchronizedToken;
    var deleteCalls = 0;

    final authenticated = await auth.synchronizeAuthStateOnStartup(
      readReauthenticationRequired: () async => false,
      readSecureToken: () async => 'existing-token',
      deleteSecureToken: () async => deleteCalls += 1,
      synchronizeNativeToken: (token) async {
        synchronizedToken = token;
        return true;
      },
    );

    expect(authenticated, isTrue);
    expect(synchronizedToken, 'existing-token');
    expect(deleteCalls, 0);
  });

  test(
    'ordinary sync rejection deletes stale token and stays unauthenticated',
    () async {
      var deleteCalls = 0;

      final authenticated = await auth.synchronizeAuthStateOnStartup(
        readReauthenticationRequired: () async => false,
        readSecureToken: () async => 'stale-token',
        deleteSecureToken: () async => deleteCalls += 1,
        synchronizeNativeToken: (_) async => false,
      );

      expect(authenticated, isFalse);
      expect(deleteCalls, 1);
    },
  );

  test(
    'ordinary sync exception fails safe without deleting secure token',
    () async {
      var deleteCalls = 0;

      final authenticated = await auth.synchronizeAuthStateOnStartup(
        readReauthenticationRequired: () async => false,
        readSecureToken: () async => 'existing-token',
        deleteSecureToken: () async => deleteCalls += 1,
        synchronizeNativeToken: (_) async => throw StateError('unavailable'),
      );

      expect(authenticated, isFalse);
      expect(deleteCalls, 0);
    },
  );

  test(
    'reauthentication deletes a stale secure token without syncing',
    () async {
      var deleteCalls = 0;
      var syncCalls = 0;

      final authenticated = await auth.synchronizeAuthStateOnStartup(
        readReauthenticationRequired: () async => true,
        readSecureToken: () async => 'stale-token',
        deleteSecureToken: () async => deleteCalls += 1,
        synchronizeNativeToken: (_) async {
          syncCalls += 1;
          return true;
        },
      );

      expect(authenticated, isFalse);
      expect(deleteCalls, 1);
      expect(syncCalls, 0);
    },
  );

  test(
    'reauthentication with no secure token is safe and does not sync',
    () async {
      var syncCalls = 0;

      final authenticated = await auth.synchronizeAuthStateOnStartup(
        readReauthenticationRequired: () async => true,
        readSecureToken: () async => null,
        deleteSecureToken: () async {},
        synchronizeNativeToken: (_) async {
          syncCalls += 1;
          return true;
        },
      );

      expect(authenticated, isFalse);
      expect(syncCalls, 0);
    },
  );

  test(
    'native state query failure fails safe without reading or syncing token',
    () async {
      var readTokenCalls = 0;
      var syncCalls = 0;

      final authenticated = await auth.synchronizeAuthStateOnStartup(
        readReauthenticationRequired: () async =>
            throw StateError('unavailable'),
        readSecureToken: () async {
          readTokenCalls += 1;
          return 'stale-token';
        },
        deleteSecureToken: () async {},
        synchronizeNativeToken: (_) async {
          syncCalls += 1;
          return true;
        },
      );

      expect(authenticated, isFalse);
      expect(readTokenCalls, 0);
      expect(syncCalls, 0);
    },
  );
}
