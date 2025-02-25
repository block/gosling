import 'dart:io';
import 'dart:convert';
import 'package:gosling/shared/widgets/platform.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shelf/shelf.dart';
import 'package:shelf/shelf_io.dart';
import 'package:shelf_router/shelf_router.dart';

final server = Provider((ref) => ServerService());

class ServerService {
  static final ServerService _instance = ServerService._internal();
  factory ServerService() => _instance;
  ServerService._internal();

  HttpServer? _server;
  bool get isRunning => _server != null;

  Future<List<String>> getLocalIpAddresses() async {
    final interfaces = await NetworkInterface.list();
    final addresses = <String>[];

    for (var interface in interfaces) {
      for (var addr in interface.addresses) {
        // Only include IPv4 addresses that aren't localhost
        if (addr.type == InternetAddressType.IPv4 &&
            !addr.address.startsWith('127.')) {
          addresses.add(addr.address);
        }
      }
    }
    return addresses;
  }

  Router _createRouter() {
    final router = Router();

    router.get('/', (Request request) async {
      final response = {
        'status': 'ok',
        'message': 'Hello from Gosling!',
        'server': {
          'localUrl': 'http://localhost:${_server?.port}',
        },
        'timestamp': DateTime.now().toIso8601String(),
      };

      return Response.ok(
        json.encode(response),
        headers: {
          'content-type': 'application/json',
          'access-control-allow-origin': '*', // Allow CORS
        },
      );
    });

    return router;
  }

  Future<String> startServer() async {
    if (!isDesktop) {
      return 'Server can only be started on desktop platforms';
    }

    if (isRunning) {
      return 'Server is already running';
    }

    try {
      final handler = Pipeline()
          .addMiddleware(logRequests())
          .addHandler(_createRouter().call);

      _server = await serve(
        handler,
        '0.0.0.0', // Allow connections from any IP
        3000,
      );

      final addresses = await getLocalIpAddresses();
      final formattedAddresses = addresses
          .map((ip) {
            if (ip.startsWith('192.168') && !ip.endsWith('.0')) {
              return '$ip:${_server!.port} (👈 Try this one first)';
            }
            return '$ip:${_server!.port}';
          })
          .map((ip) => 'http://$ip')
          .join('\n');

      return '''Server started!

You can access it from this computer at:
http://localhost:${_server!.port}

Or from other devices on your network at:
(Add the port number to the end of these addresses)
$formattedAddresses''';
    } catch (e) {
      return 'Failed to start server: $e';
    }
  }

  Future<String> stopServer() async {
    if (!isDesktop) {
      return 'Server can only be stopped on desktop platforms';
    }

    if (!isRunning) {
      return 'Server is not running';
    }

    try {
      await _server?.close();
      _server = null;
      return 'Server stopped';
    } catch (e) {
      return 'Failed to stop server: $e';
    }
  }
}
