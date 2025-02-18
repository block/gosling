import 'package:flutter/material.dart';
import 'package:flutter_svg/svg.dart';
import 'package:gosling/features/chat/message.dart';
import 'package:gosling/shared/theme/grid.dart';
import 'package:gosling/shared/widgets/loader.dart';

class MessageBubble extends StatelessWidget {
  final Message message;

  const MessageBubble({
    super.key,
    required this.message,
  });

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: message.isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Stack(
        children: [
          Container(
            margin: const EdgeInsets.symmetric(
                horizontal: Grid.sm, vertical: Grid.sm),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: message.isUser
                  ? Theme.of(context).colorScheme.primaryContainer
                  : Theme.of(context).colorScheme.secondaryContainer,
              borderRadius: message.isUser
                  ? const BorderRadius.only(
                      topLeft: Radius.circular(14),
                      topRight: Radius.circular(14),
                      bottomLeft: Radius.circular(14),
                      bottomRight: Radius.circular(4),
                    )
                  : const BorderRadius.only(
                      topLeft: Radius.circular(14),
                      topRight: Radius.circular(14),
                      bottomLeft: Radius.circular(4),
                      bottomRight: Radius.circular(14),
                    ),
            ),
            child: (message.isLoading)
                ? const Loader()
                : Text(
                    message.text,
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: message.isUser
                              ? Theme.of(context).colorScheme.onPrimaryContainer
                              : Theme.of(context).colorScheme.onSurface,
                        ),
                  ),
          ),
          if (!message.isUser) ...[
            SvgPicture.asset(
              'assets/images/chat_goose.svg',
              width: 21,
              height: 21,
            ),
            const SizedBox(height: Grid.xs),
          ],
        ],
      ),
    );
  }
}
