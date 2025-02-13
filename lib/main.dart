import 'package:flutter/material.dart';
import 'package:gosling/features/app/app.dart';
import 'package:gosling/generated/rust/frb_generated.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

Future<void> main() async {
  await RustLib.init();
  runApp(const ProviderScope(child: App()));
}
