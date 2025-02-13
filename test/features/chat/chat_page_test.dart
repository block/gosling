import 'package:flutter/material.dart';
import 'package:flutter_starter/features/chat/chat_page.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../helpers/widget_helpers.dart';

void main() {
  testWidgets('should have logo and input field', (WidgetTester tester) async {
    await tester.pumpWidget(
      WidgetHelpers.testableWidget(child: const ChatPage()),
    );

    expect(find.byType(SvgPicture), findsOneWidget);
    expect(find.byType(TextField), findsOneWidget);
  });
}
