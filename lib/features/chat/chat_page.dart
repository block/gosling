import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:gosling/l10n/app_localizations.dart';
import 'package:gosling/features/chat/message.dart';
import 'package:gosling/features/chat/message_bubble.dart';
import 'package:gosling/shared/theme/grid.dart';
import 'package:http/http.dart' as http;
import 'dart:convert';

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
          Expanded(
            child: showWelcomeImage.value
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(Grid.md),
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
                    padding: const EdgeInsets.all(Grid.sm),
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
            padding: EdgeInsets.only(
              top: Grid.sm,
              bottom: Grid.lg + MediaQuery.of(context).viewInsets.bottom,
            ),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.onPrimaryContainer,
              borderRadius:
                  const BorderRadius.vertical(top: Radius.circular(16)),
              boxShadow: [
                BoxShadow(
                  color: Theme.of(context)
                      .colorScheme
                      .shadow
                      .withValues(alpha: 0.1),
                  blurRadius: 4,
                  offset: const Offset(0, -1),
                ),
              ],
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Padding(
                  padding: const EdgeInsets.only(left: Grid.md, right: Grid.sm),
                  child: Row(
                    children: [
                      Expanded(
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(
                            maxHeight: 120,
                          ),
                          child: TextField(
                            controller: messageController,
                            minLines: 1,
                            maxLines: 4,
                            decoration: InputDecoration(
                              hintText: Loc.of(context).whatShouldGooseDo,
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: Grid.sm),
                      IconButton(
                        key: const Key('send_message_button'),
                        onPressed: () {
                          _onSendMessage(
                            messageController.text,
                            messages,
                            showWelcomeImage,
                            messageController,
                          );
                        },
                        icon: Icon(
                          Icons.send,
                          color: Theme.of(context).colorScheme.primary,
                        ),
                      ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(
                    left: Grid.xs,
                    right: Grid.sm,
                  ),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Row(spacing: 1, children: [
                      IconButton(
                        onPressed: () {},
                        style: IconButton.styleFrom(
                          foregroundColor:
                              Theme.of(context).colorScheme.secondary,
                        ),
                        icon: Icon(Icons.link),
                      ),
                      IconButton(
                        onPressed: () {},
                        style: IconButton.styleFrom(
                          foregroundColor:
                              Theme.of(context).colorScheme.secondary,
                        ),
                        icon: Icon(Icons.remove_red_eye_rounded),
                      ),
                      IconButton(
                        onPressed: () {},
                        style: IconButton.styleFrom(
                          foregroundColor:
                              Theme.of(context).colorScheme.secondary,
                        ),
                        icon: Icon(Icons.code),
                      ),
                    ]),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _cleanOutput(String text) {
    // Remove ANSI escape codes
    text = text.replaceAll(RegExp(r'\u001b\[[0-9;]*m'), '');
    
    // Remove the session information header (first two lines)
    final lines = text.split('\n');
    if (lines.length > 2 && lines[0].contains('starting session')) {
      lines.removeRange(0, 2);
      text = lines.join('\n');
    }
    
    // Trim extra whitespace while preserving intentional line breaks
    text = text.split('\n').map((line) => line.trim()).join('\n');
    text = text.replaceAll(RegExp(r'\n{3,}'), '\n\n'); // Replace multiple blank lines with double line break
    
    return text.trim();
  }

  void _onSendMessage(
    String message,
    ValueNotifier<List<Message>> messages,
    ValueNotifier<bool> showWelcomeImage,
    TextEditingController messageController,
  ) async {
    if (message.trim().isEmpty) return;

    showWelcomeImage.value = false;
    messages.value = [
      ...messages.value,
      Message(text: message, isUser: true),
      Message(text: '', isUser: false, isLoading: true),
    ];

    messageController.clear();

    try {
      final response = await http.post(
        Uri.parse('https://captured-jp-samoa-hardly.trycloudflare.com/run'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'command': message}),
      );

      if (response.statusCode == 200) {
        final jsonResponse = jsonDecode(response.body);
        final output = _cleanOutput(jsonResponse['output'] as String);
        final error = jsonResponse['error'] as String?;

        final responseText = error != null && error.isNotEmpty 
            ? 'Error: $error\n$output' 
            : output;

        messages.value = [
          ...messages.value.sublist(0, messages.value.length - 1),
          Message(text: responseText, isUser: false),
        ];
      } else {
        messages.value = [
          ...messages.value.sublist(0, messages.value.length - 1),
          Message(text: 'Error: HTTP ${response.statusCode}', isUser: false),
        ];
      }
    } catch (e) {
      messages.value = [
        ...messages.value.sublist(0, messages.value.length - 1),
        Message(text: 'Error: $e', isUser: false),
      ];
    }
  }
}
