import 'package:flutter/material.dart';
import 'package:gosling/features/chat/chat_page.dart';
import 'package:gosling/l10n/app_localizations.dart';
import 'package:gosling/shared/theme/theme.dart';

class App extends StatelessWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: "Gosling",
      theme: lightTheme(context),
      darkTheme: darkTheme(context),
      home: const ChatPage(),
      localizationsDelegates: Loc.localizationsDelegates,
      supportedLocales: const [
        Locale('en', ''),
      ],
    );
  }
}
