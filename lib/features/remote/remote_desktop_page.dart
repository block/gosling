import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:gosling/features/remote/remote_server.dart';

class RemoteDesktopPage extends HookConsumerWidget {
  const RemoteDesktopPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final serverInfo = useState<String?>(null);
    final serverInstance = ref.watch(server);

    useEffect(() {
      Future<void> checkServerAndUpdateInfo() async {
        debugPrint('Checking server status...');
        if (serverInstance.isRunning) {
          debugPrint('Server is running, getting addresses...');
          final addresses = await serverInstance.getLocalIpAddresses();
          debugPrint('Got addresses: $addresses');

          if (addresses.isNotEmpty) {
            final mainAddress = addresses.firstWhere(
              (addr) => addr.startsWith('192.168') && !addr.endsWith('.0'),
              orElse: () => addresses.first,
            );
            debugPrint('Selected address: $mainAddress');
            serverInfo.value = 'http://$mainAddress:3000';
          }
        } else {
          debugPrint('Server is not running');
        }
      }

      // Initial check
      checkServerAndUpdateInfo();

      // Set up periodic check every 2 seconds until we get an address
      final timer = Timer.periodic(const Duration(seconds: 2), (timer) {
        if (serverInfo.value != null) {
          timer.cancel();
        } else {
          checkServerAndUpdateInfo();
        }
      });

      return () {
        timer.cancel();
      };
    }, [serverInstance]);

    if (serverInfo.value == null) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const CircularProgressIndicator(),
              const SizedBox(height: 16),
              Text(
                'Waiting for server...',
                style: Theme.of(context).textTheme.bodyLarge,
              ),
            ],
          ),
        ),
      );
    }

    return Scaffold(
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text(
              'Scan to connect',
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 20),
            QrImageView(
              data: serverInfo.value!,
              version: QrVersions.auto,
              size: 200.0,
              eyeStyle: QrEyeStyle(
                eyeShape: QrEyeShape.square,
                color: Theme.of(context).colorScheme.onSurface,
              ),
              dataModuleStyle: QrDataModuleStyle(
                dataModuleShape: QrDataModuleShape.square,
                color: Theme.of(context).colorScheme.onSurface,
              ),
            ),
            const SizedBox(height: 20),
            SelectableText(
              serverInfo.value!,
              style: const TextStyle(
                fontSize: 16,
                fontFamily: 'monospace',
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Tap the URL above to copy',
              style: TextStyle(
                fontSize: 12,
                color: Colors.grey,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
