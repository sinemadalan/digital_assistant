import 'package:accessibility_service/pages/control_panel.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const commands = MethodChannel('com.your.package/accessibility_commands');
  const events = MethodChannel('com.your.package/accessibility_status');
  const authEvents = MethodChannel('com.your.package/auth_status');
  var status = 'ready';
  var grantOnRequest = true;
  var permissionRequests = 0;

  setUp(() {
    status = 'ready';
    grantOnRequest = true;
    permissionRequests = 0;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(events, (_) async => null);
    messenger.setMockMethodCallHandler(authEvents, (_) async => null);
    messenger.setMockMethodCallHandler(commands, (call) async {
      switch (call.method) {
        case 'notificationAvailability':
          return status;
        case 'requestNotificationPermission':
          permissionRequests++;
          if (grantOnRequest) status = 'ready';
          return null;
        case 'testLocalNotification':
          return status == 'ready';
        default:
          return null;
      }
    });
  });

  tearDown(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(commands, null);
    messenger.setMockMethodCallHandler(events, null);
    messenger.setMockMethodCallHandler(authEvents, null);
  });

  Future<void> openPanel(WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: ControlPanel()));
    await tester.pumpAndSettle();
  }

  testWidgets('revoked enrollment is reported without leaving the panel', (
    tester,
  ) async {
    await openPanel(tester);
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    await messenger.handlePlatformMessage(
      'com.your.package/auth_status',
      const StandardMethodCodec().encodeSuccessEnvelope(true),
      null,
    );
    await tester.pumpAndSettle();
    expect(find.text('Yeniden eşleştir'), findsOneWidget);
    expect(
      find.text(
        'Cihaz bağlantısı geçersiz. Yeni bir eşleştirme koduyla tekrar bağlanın.',
      ),
      findsOneWidget,
    );
    await messenger.handlePlatformMessage(
      'com.your.package/auth_status',
      const StandardMethodCodec().encodeSuccessEnvelope(false),
      null,
    );
    await tester.pumpAndSettle();
    expect(find.text('Yeniden eşleştir'), findsNothing);
  });

  testWidgets('permission grant refreshes notification status', (tester) async {
    status = 'permission_required';
    await openPanel(tester);
    expect(
      find.text('Bildirim göstermek için izin gerekiyor.'),
      findsOneWidget,
    );
    await tester.tap(find.text('Bildirimleri aç'));
    await tester.pumpAndSettle();
    expect(permissionRequests, 1);
    expect(find.text('İçerik bildirimleri açık.'), findsOneWidget);
  });

  testWidgets('denial keeps pending notification explanation', (tester) async {
    status = 'permission_required';
    grantOnRequest = false;
    await openPanel(tester);
    await tester.tap(find.text('Bildirimleri aç'));
    await tester.pumpAndSettle();
    expect(
      find.text('Bildirim göstermek için izin gerekiyor.'),
      findsOneWidget,
    );
    expect(
      find.text('Kaydedilmiş bildirimler izin açılana kadar bekler.'),
      findsOneWidget,
    );
  });

  testWidgets('channel settings changes are picked up on resume', (
    tester,
  ) async {
    status = 'channel_disabled';
    await openPanel(tester);
    expect(find.text('İçerik bildirimi kanalı kapalı.'), findsOneWidget);
    status = 'ready';
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(find.text('İçerik bildirimleri açık.'), findsOneWidget);
  });

  testWidgets('local test failure does not report a sent notification', (
    tester,
  ) async {
    status = 'app_disabled';
    await openPanel(tester);
    await tester.ensureVisible(find.text('Test local notification'));
    await tester.tap(find.text('Test local notification'));
    await tester.pump();
    expect(
      find.text(
        'Bildirim gönderilemedi. İzin ve kanal ayarlarını kontrol edin.',
      ),
      findsOneWidget,
    );
  });
}
