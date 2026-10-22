import 'dart:async';
import 'package:flutter/material.dart';

import '../Util/accessibility_service_manager.dart';
import '../Util/user_auth_manager.dart' as auth;
import 'login_page.dart';

class ControlPanel extends StatefulWidget {
  const ControlPanel({super.key});

  @override
  State<ControlPanel> createState() => _ControlPanelState();
}

class _ControlPanelState extends State<ControlPanel>
    with WidgetsBindingObserver {
  bool _isPaused = false;
  bool _reauthenticationRequired = false;
  StreamSubscription<bool>? _authSubscription;
  bool _permissionBusy = false;
  String _notificationAvailability = 'checking';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshNotificationAvailability();
    _authSubscription = AccessibilityManager.reauthenticationRequiredStream
        .listen((required) {
          if (mounted) setState(() => _reauthenticationRequired = required);
        }, onError: (_) {});
  }

  @override
  void dispose() {
    _authSubscription?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refreshNotificationAvailability();
  }

  Future<void> _refreshNotificationAvailability() async {
    try {
      final state = await AccessibilityManager.notificationAvailability();
      if (mounted) setState(() => _notificationAvailability = state);
    } catch (_) {
      if (mounted) setState(() => _notificationAvailability = 'unavailable');
    }
  }

  Future<void> _reenroll() async {
    try {
      await auth.deleteTokenFromSecureStorage();
      if (!mounted) return;
      Navigator.of(context).pushAndRemoveUntil(
        MaterialPageRoute<void>(builder: (_) => const LoginPage()),
        (_) => false,
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Cihaz kaydı yenilenemedi. Tekrar deneyin.'),
          ),
        );
      }
    }
  }

  Future<void> _configureNotifications() async {
    setState(() => _permissionBusy = true);
    try {
      await AccessibilityManager.requestNotificationPermission();
      await _refreshNotificationAvailability();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Bildirim ayarları açılamadı.')),
        );
      }
    } finally {
      if (mounted) setState(() => _permissionBusy = false);
    }
  }

  String get _notificationMessage => switch (_notificationAvailability) {
    'ready' => 'İçerik bildirimleri açık.',
    'permission_required' => 'Bildirim göstermek için izin gerekiyor.',
    'app_disabled' => 'Uygulama bildirimleri ayarlarda kapalı.',
    'channel_disabled' => 'İçerik bildirimi kanalı kapalı.',
    'checking' => 'Bildirim ayarları kontrol ediliyor…',
    _ => 'Bildirim durumu kontrol edilemedi.',
  };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        title: const Text('Accessibility Control'),
        actions: [
          TextButton.icon(
            icon: const Icon(Icons.navigate_before),
            label: const Text('Go back to login page'),
            onPressed: () => Navigator.pop(context),
          ),
        ],
      ),
      body: StreamBuilder<bool>(
        stream: AccessibilityManager.serviceStatusStream,
        initialData: false,
        builder: (context, snapshot) {
          final isRunning = snapshot.data ?? false;
          return Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    isRunning
                        ? Icons.check_circle
                        : Icons.warning_amber_rounded,
                    color: isRunning ? Colors.green : Colors.red,
                    size: 64,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    isRunning ? 'Service is ENABLED' : 'Service is DISABLED',
                    style: const TextStyle(
                      fontSize: 22,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 30),
                  if (!isRunning)
                    ElevatedButton.icon(
                      onPressed: AccessibilityManager.openSettings,
                      icon: const Icon(Icons.settings),
                      label: const Text('Grant Permission in Settings'),
                    )
                  else
                    Card(
                      child: SwitchListTile(
                        title: const Text('Pause Service (Save CPU)'),
                        subtitle: const Text(
                          'Stops listening to events without revoking permission',
                        ),
                        value: _isPaused,
                        onChanged: (value) {
                          setState(() => _isPaused = value);
                          AccessibilityManager.setPaused(value);
                        },
                      ),
                    ),
                  const SizedBox(height: 24),
                  if (_reauthenticationRequired)
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          children: [
                            const Text(
                              'Cihaz bağlantısı geçersiz. Yeni bir eşleştirme koduyla tekrar bağlanın.',
                            ),
                            TextButton(
                              onPressed: _reenroll,
                              child: const Text('Yeniden eşleştir'),
                            ),
                          ],
                        ),
                      ),
                    ),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        children: [
                          Text(_notificationMessage),
                          if (_notificationAvailability != 'ready' &&
                              _notificationAvailability != 'checking')
                            const Padding(
                              padding: EdgeInsets.only(top: 8),
                              child: Text(
                                'Kaydedilmiş bildirimler izin açılana kadar bekler.',
                              ),
                            ),
                          const SizedBox(height: 8),
                          TextButton.icon(
                            onPressed:
                                _permissionBusy ||
                                    _notificationAvailability == 'checking' ||
                                    _notificationAvailability == 'unavailable'
                                ? null
                                : _configureNotifications,
                            icon: const Icon(Icons.notifications_outlined),
                            label: Text(
                              _notificationAvailability == 'ready'
                                  ? 'Bildirim ayarları'
                                  : 'Bildirimleri aç',
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  ElevatedButton.icon(
                    onPressed: _sendTestNotification,
                    icon: const Icon(Icons.notifications_active),
                    label: const Text('Test local notification'),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Future<void> _sendTestNotification() async {
    try {
      final posted = await AccessibilityManager.testLocalNotification();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            posted
                ? 'Test bildirimi gönderildi.'
                : 'Bildirim gönderilemedi. İzin ve kanal ayarlarını kontrol edin.',
          ),
        ),
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Test bildirimi gönderilemedi.')),
        );
      }
    }
    await _refreshNotificationAvailability();
  }
}
