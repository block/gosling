import 'package:flutter/material.dart';
import 'package:gosling/features/chat/chat_page.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gosling/generated/rust/frb_generated.dart';
import 'package:mocktail/mocktail.dart';

import '../../helpers/mocks.dart';
import '../../helpers/widget_helpers.dart';

Future<void> main() async {
  final mockApi = MockRustLibApi();
  RustLib.initMock(api: mockApi);

  testWidgets('should have correct initial content',
      (WidgetTester tester) async {
    await tester.pumpWidget(
      WidgetHelpers.testableWidget(child: const ChatPage()),
    );

    expect(find.byType(SvgPicture), findsOneWidget);
    expect(find.byType(TextField), findsOneWidget);
    expect(find.byKey(const Key('send_message_button')), findsOneWidget);
  });

  testWidgets('should send message when button is pressed',
      (WidgetTester tester) async {
    when(() => mockApi.crateApiSimpleGreet(name: "Hello"))
        .thenReturn("Hello Test");

    await tester.pumpWidget(
      WidgetHelpers.testableWidget(child: const ChatPage()),
    );

    await tester.enterText(find.byType(TextField), 'Hello');
    await tester.tap(find.byKey(const Key('send_message_button')));
    await tester.pump();

    expect(find.text('Hello Test'), findsOneWidget);
  });
}
