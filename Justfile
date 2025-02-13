_help:

# Run the Flutter app
run:
  flutter run

# Get Flutter and Dart packages
get:
  #!/bin/bash
  echo "Getting dependencies for main project"
  flutter pub get

# Clean the project
clean:
  flutter clean

# Build the app for release
build:
  flutter build apk

# Run tests
test:
  flutter test

# Analyze the project's Dart code
analyze:
  #!/bin/bash
  flutter analyze

# Generate code (localization, etc.)
generate:
  flutter gen-l10n

# Coverage report
coverage:
  #!/bin/bash
  flutter test --coverage
  genhtml coverage/lcov.info -o coverage/html
  open coverage/html/index.html
