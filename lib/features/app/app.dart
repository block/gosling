import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gosling/features/chat/chat_page.dart';
import 'package:gosling/features/remote/remote_desktop_page.dart';
import 'package:gosling/features/remote/remote_mobile_page.dart';
import 'package:gosling/features/remote/remote_server.dart';
import 'package:gosling/l10n/app_localizations.dart';
import 'package:gosling/shared/theme/theme.dart';
import 'package:gosling/shared/widgets/platform.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

class App extends HookConsumerWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selectedIndex = useState(0);

    useEffect(() {
      Future.microtask(() async {
        if (isDesktop) {
          final result = await ref.read(server).startServer();
          debugPrint(result);
        }
      });
      return null;
    }, []);

    return MaterialApp(
      title: "Gosling",
      theme: lightTheme(context),
      darkTheme: darkTheme(context),
      home: Builder(
        builder: (context) => Scaffold(
          appBar: AppBar(),
          body: IndexedStack(
            index: selectedIndex.value,
            children: [
              ChatPage(),
              isDesktop ? RemoteDesktopPage() : RemoteMobilePage(),
            ],
          ),
          drawer: NavigationDrawer(
            selectedIndex: selectedIndex.value,
            onDestinationSelected: (index) {
              selectedIndex.value = index;
              Navigator.pop(context);
            },
            children: const [
              NavigationDrawerDestination(
                icon: Icon(Icons.chat),
                label: Text('Chat'),
              ),
              NavigationDrawerDestination(
                icon: Icon(Icons.computer),
                label: Text('Remote Control'),
              ),
            ],
          ),
        ),
      ),
      localizationsDelegates: Loc.localizationsDelegates,
      supportedLocales: const [
        Locale('en', ''),
      ],
    );
  }
}
