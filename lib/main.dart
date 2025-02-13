import 'package:flutter/material.dart';
import 'package:flutter_starter/features/app/app.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

void main() {
  runApp(const ProviderScope(child: App()));
}
