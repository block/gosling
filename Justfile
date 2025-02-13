_help:
  @just -l

# Setup the environment. Useful for initial development setup.
setup:
  #!/bin/bash
  rustup default stable
  cargo install flutter_rust_bridge_codegen

# Run the Flutter app
run:
  flutter run

# Get Flutter and Dart packages
get:
  #!/bin/bash
  echo "Getting dependencies for main project"
  flutter pub get

  echo "Getting dependencies for all Dart/Flutter packages..."
  
  # Find all pubspec.yaml files
  find rust_builder -name "pubspec.yaml" | while read pubspec; do
    dir=$(dirname "$pubspec")
    echo "Installing dependencies in $dir"
    cd "$dir"
    
    # Try flutter pub get first, fall back to dart pub get if it fails
    flutter pub get > /dev/null 2>&1 || dart pub get
    
    cd - > /dev/null
  done

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
gen-l10n:
  flutter gen-l10n

# Generate the Rust bindings
generate-rust:
  flutter_rust_bridge_codegen generate

# Generate the Rust bindings
generate: gen-l10n generate-rust

# Coverage report
coverage:
  #!/bin/bash
  flutter test --coverage
  genhtml coverage/lcov.info -o coverage/html
  open coverage/html/index.html


