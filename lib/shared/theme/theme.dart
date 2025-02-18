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
    inputDecorationTheme: createInputDecorationTheme(lightColorScheme),
  );
}

ThemeData darkTheme(BuildContext context) {
  final base = ThemeData.from(
    colorScheme: darkColorScheme,
    textTheme: textTheme,
    useMaterial3: true,
  );

  return base.copyWith(
    inputDecorationTheme: createInputDecorationTheme(darkColorScheme),
  );
}

InputDecorationTheme createInputDecorationTheme(ColorScheme colorScheme) {
  return InputDecorationTheme(
    hintStyle: TextStyle(color: colorScheme.onSurface.withValues(alpha: 0.5)),
    isDense: true,
    border: OutlineInputBorder(borderSide: BorderSide.none),
    contentPadding: const EdgeInsets.symmetric(horizontal: 0),
  );
}
