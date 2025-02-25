import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:http/http.dart' as http;

class RemoteMobilePage extends HookWidget {
  const RemoteMobilePage({super.key});

  @override
  Widget build(BuildContext context) {
    final scannerController = useMemoized(() => MobileScannerController());
    final hasScanned = useState(false);
    final scannedUrl = useState<String?>(null);
    final serverResponse = useState<Map<String, dynamic>?>(null);
    final isLoading = useState(false);
    final error = useState<String?>(null);

    Future<void> fetchServerInfo(String url) async {
      isLoading.value = true;
      error.value = null;
      try {
        final response = await http.get(Uri.parse(url));
        if (response.statusCode == 200) {
          serverResponse.value = json.decode(response.body);
        } else {
          error.value = 'Server returned ${response.statusCode}';
        }
      } catch (e) {
        error.value = 'Failed to connect: $e';
      } finally {
        isLoading.value = false;
      }
    }

    useEffect(() {
      return () {
        scannerController.dispose();
      };
    }, []);

    Widget buildContent() {
      if (!hasScanned.value) {
        return Column(
          children: [
            Expanded(
              child: MobileScanner(
                controller: scannerController,
                onDetect: (capture) {
                  final List<Barcode> barcodes = capture.barcodes;
                  for (final barcode in barcodes) {
                    if (!hasScanned.value && barcode.rawValue != null) {
                      hasScanned.value = true;
                      scannedUrl.value = barcode.rawValue;
                      fetchServerInfo(barcode.rawValue!);
                    }
                  }
                },
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(16.0),
              child: Text(
                'Point your camera at the QR code shown on your desktop',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyLarge,
              ),
            ),
          ],
        );
      }

      return Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Connected to:',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            SelectableText(
              scannedUrl.value ?? '',
              style: const TextStyle(
                fontFamily: 'monospace',
                fontSize: 16,
              ),
            ),
            const SizedBox(height: 24),
            if (isLoading.value) ...[
              const Center(child: CircularProgressIndicator()),
              const SizedBox(height: 16),
            ],
            if (error.value != null) ...[
              Text(
                'Error:',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      color: Theme.of(context).colorScheme.error,
                    ),
              ),
              const SizedBox(height: 8),
              Text(
                error.value!,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.error,
                ),
              ),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: () {
                  if (scannedUrl.value != null) {
                    fetchServerInfo(scannedUrl.value!);
                  }
                },
                child: const Text('Retry Connection'),
              ),
            ],
            if (serverResponse.value != null) ...[
              Text(
                'Server Info:',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 8),
              Expanded(
                child: Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surface,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: SingleChildScrollView(
                    child: SelectableText(
                      const JsonEncoder.withIndent('  ')
                          .convert(serverResponse.value),
                      style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 14,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ],
        ),
      );
    }

    return Scaffold(
      body: buildContent(),
    );
  }
}
