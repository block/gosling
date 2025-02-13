import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_starter/l10n/app_localizations.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

class WidgetHelpers {
  static Widget testableWidget({
    required Widget child,
    List<Override> overrides = const [],
  }) {
    return ProviderScope(
      overrides: overrides,
      child: MaterialApp(
        localizationsDelegates: const [
          Loc.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
        ],
        supportedLocales: const [
          Locale('en', ''),
        ],
        home: Scaffold(body: child),
      ),
    );
  }
}
