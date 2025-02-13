import 'package:flutter/material.dart';
import 'package:flutter_starter/shared/theme/color_scheme.dart';
import 'package:flutter_starter/shared/theme/text_theme.dart';

ThemeData lightTheme(BuildContext context) => ThemeData(
      useMaterial3: true,
      colorScheme: lightColorScheme,
      textTheme: textTheme,
    );

ThemeData darkTheme(BuildContext context) => ThemeData(
      useMaterial3: true,
      colorScheme: darkColorScheme,
      textTheme: textTheme,
    );
