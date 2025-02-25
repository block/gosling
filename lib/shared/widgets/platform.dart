import 'dart:io';

import 'package:flutter/foundation.dart';

bool get isDesktop =>
    !kIsWeb && (Platform.isMacOS || Platform.isWindows || Platform.isLinux);
