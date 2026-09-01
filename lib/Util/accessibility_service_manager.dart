import 'package:flutter/services.dart';

class AccessibilityManager {
  static const EventChannel _eventChannel = EventChannel('com.your.package/accessibility_status');
  static const MethodChannel _methodChannel = MethodChannel('com.your.package/accessibility_commands');

  /// Returns a stream that actively listens to the Android service lifecycle.
  /// Use this in a StreamBuilder in your UI.
  static Stream<bool> get serviceStatusStream {
    return _eventChannel.receiveBroadcastStream().map((event) => event as bool);
  }

  /// Tells the native side to pause (stop listening to events) or resume.
  static Future<void> setPaused(bool pause) async {
    await _methodChannel.invokeMethod('setPaused', {'pause': pause});
  }

  /// Opens the Android Accessibility Settings page.
  static Future<void> openSettings() async {
    await _methodChannel.invokeMethod('openSettings');
  }

  /// Persists the authentication token in Android's native encrypted store.
  static Future<void> setAuthToken(String token) async {
    if (token.trim().isEmpty) {
      throw ArgumentError('Token must not be blank');
    }
    await _methodChannel.invokeMethod('setAuthToken', {'token': token});
  }

  /// Removes the authentication token from Android's native encrypted store.
  static Future<void> clearAuthToken() async {
    await _methodChannel.invokeMethod('clearAuthToken');
  }
}
