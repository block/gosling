import 'package:flutter/material.dart';
import 'package:gosling/shared/theme/color_scheme.dart';
import 'package:gosling/shared/theme/text_theme.dart';

ThemeData lightTheme(BuildContext context) {
  final base = ThemeData.from(
    colorScheme: lightColorScheme,
    textTheme: textTheme,
    useMaterial3: true,
  );

  return base.copyWith(
    inputDecorationTheme: base.inputDecorationTheme.copyWith(
      hintStyle: TextStyle(
        color: lightColorScheme.onSurface.withValues(alpha: 0.5),
      ),
      border: OutlineInputBorder(
        borderSide: BorderSide.none,
      ),
      contentPadding: const EdgeInsets.symmetric(
        horizontal: 6,
      ),
    ),
  );
}

ThemeData darkTheme(BuildContext context) {
  final base = ThemeData.from(
    colorScheme: darkColorScheme,
    textTheme: textTheme,
    useMaterial3: true,
  );

  return base.copyWith(
    inputDecorationTheme: base.inputDecorationTheme.copyWith(
      hintStyle: TextStyle(
        color: darkColorScheme.onSurface.withValues(alpha: 0.5),
      ),
      border: OutlineInputBorder(
        borderSide: BorderSide.none,
      ),
      contentPadding: const EdgeInsets.symmetric(
        horizontal: 6,
      ),
    ),
  );
}
