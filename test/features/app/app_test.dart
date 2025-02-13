import 'package:gosling/features/app/app.dart';
import 'package:gosling/features/chat/chat_page.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../helpers/widget_helpers.dart';

void main() {
  testWidgets('should show AppTabs', (WidgetTester tester) async {
    await tester.pumpWidget(
      WidgetHelpers.testableWidget(child: const App()),
    );

    expect(find.byType(ChatPage), findsOneWidget);
  });
}
