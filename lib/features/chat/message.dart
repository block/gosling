class Message {
  final String text;
  final bool isUser;
  final bool isLoading;
  final DateTime timestamp;

  Message({
    required this.text,
    required this.isUser,
    this.isLoading = false,
    DateTime? timestamp,
  }) : timestamp = timestamp ?? DateTime.now();
}
