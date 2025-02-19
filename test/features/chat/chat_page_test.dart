import 'dart:async';

import 'package:flutter/material.dart';
import 'package:gosling/features/chat/chat_page.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gosling/generated/rust/frb_generated.dart';
import 'package:gosling/shared/widgets/loader.dart';
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

  testWidgets(
      'should show loader while waiting for response and then show message',
      (WidgetTester tester) async {
    // Setup delayed response
    final completer = Completer<String>();
    when(() => mockApi.crateApiSimpleGreetWithDelay(name: "Hello"))
        .thenAnswer((_) => completer.future);

    await tester.pumpWidget(
      WidgetHelpers.testableWidget(child: const ChatPage()),
    );

    // Send message
    await tester.enterText(find.byType(TextField), 'Hello');
    await tester.tap(find.byKey(const Key('send_message_button')));
    await tester.pump();

    // Verify loader is shown
    expect(find.byType(Loader), findsOneWidget);
    expect(find.text('Hello Test'), findsNothing);

    // Complete the future and wait for animation
    completer.complete("Hello Test");
    await tester.pump();
    await tester.pumpAndSettle();

    // Verify loader is gone and message is shown
    expect(find.byType(Loader), findsNothing);
    expect(find.text('Hello Test'), findsOneWidget);
  });
}
