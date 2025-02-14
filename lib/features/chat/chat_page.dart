import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gosling/generated/rust/api/simple.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:gosling/l10n/app_localizations.dart';
import 'package:gosling/features/chat/message.dart';
import 'package:gosling/features/chat/message_bubble.dart';

class ChatPage extends HookWidget {
  const ChatPage({super.key});

  @override
  Widget build(BuildContext context) {
    final messageController = useTextEditingController();
    final messages = useState<List<Message>>([]);
    final showWelcomeImage = useState(true);
    final scrollController = useScrollController();

    // Add effect to auto-scroll to bottom when new messages arrive
    useEffect(() {
      if (!showWelcomeImage.value && messages.value.isNotEmpty) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          scrollController.animateTo(
            0,
            duration: const Duration(milliseconds: 200),
            curve: Curves.easeOut,
          );
        });
      }
      return null;
    }, [messages.value]);

    return Scaffold(
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Top section that takes remaining space
          Expanded(
            child: showWelcomeImage.value
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: SvgPicture.asset(
                        'assets/images/ask_goose.svg',
                        colorFilter: ColorFilter.mode(
                          Theme.of(context).colorScheme.onSurface,
                          BlendMode.srcIn,
                        ),
                        width: 200,
                      ),
                    ),
                  )
                : ListView.builder(
                    controller: scrollController,
                    reverse: true, // Make the list build from bottom up
                    padding: const EdgeInsets.all(8),
                    itemCount: messages.value.length,
                    itemBuilder: (context, index) {
                      // Reverse the index to show messages in correct order
                      final reversedIndex = messages.value.length - 1 - index;
                      return MessageBubble(
                          message: messages.value[reversedIndex]);
                    },
                  ),
          ),
          // Bottom panel
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.onPrimaryContainer,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(16),
              ),
              boxShadow: [
                BoxShadow(
                  color: Theme.of(context)
                      .colorScheme
                      .shadow
                      .withValues(alpha: 0.1),
                  blurRadius: 4,
                  offset: const Offset(0, -2),
                ),
              ],
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // Text input row
                TextField(
                  controller: messageController,
                  decoration: InputDecoration(
                    hintText: Loc.of(context).whatShouldGooseDo,
                  ),
                ),
                const SizedBox(height: 12),
                // Buttons row
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    IconButton.outlined(
                      onPressed: () {},
                      icon: const Icon(Icons.add),
                    ),
                    IconButton.filled(
                      key: const Key('send_message_button'),
                      onPressed: () {
                        _onSendMessage(
                          messageController.text,
                          messages,
                          showWelcomeImage,
                          messageController,
                        );
                      },
                      icon: const Icon(Icons.arrow_upward),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _onSendMessage(
    String message,
    ValueNotifier<List<Message>> messages,
    ValueNotifier<bool> showWelcomeImage,
    TextEditingController messageController,
  ) {
    if (message.trim().isEmpty) return;

    showWelcomeImage.value = false;
    messages.value = [
      ...messages.value,
      Message(text: message, isUser: true),
    ];

    final response = greet(name: message);
    messages.value = [
      ...messages.value,
      Message(text: response, isUser: false),
    ];

    messageController.clear();
  }
}
