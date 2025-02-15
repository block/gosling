import 'package:flutter/material.dart';
import 'package:gosling/features/app/app.dart';
import 'package:gosling/generated/rust/frb_generated.dart';
import 'package:gosling/shared/env.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

Future<void> main() async {
  // Validate the environment variables are correctly set
  Env.validate();

  await RustLib.init();

  runApp(const ProviderScope(child: App()));
}
