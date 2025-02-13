
<p align="center">
  <img src="docs/gosling.webp" alt="Gosling Logo" width="300"/>
</p>

## Getting Started

This project uses [Hermit](https://cashapp.github.io/hermit/) to manage development dependencies. Hermit ensures everyone uses the same versions of tools like Flutter, Rust, and other development dependencies.

### Prerequisites

1. Install Hermit by following the [installation guide](https://cashapp.github.io/hermit/usage/get-started/)
2. Activate Hermit in your shell (or better yet, use shell [hooks](https://cashapp.github.io/hermit/usage/shell/) to automatically activate Hermit):
   ```bash
   source bin/activate-hermit
   ```
3. Flutter will require other tools like Xcode and Android Studio to be installed. See the [Flutter installation guide](https://docs.flutter.dev/get-started/install) for more information.

### Development Setup

After cloning the repository, (and activating Hermit as described above), you can run the following command to install the dependencies. This will configure the project for flutter_rust_bridge_codegen:

```bash
just setup
```

### Updating generated files

> Note: This will run the generation for both the localization files and the Rust bindings.

```bash
just generate
```

### Running the app

It's recommended to use VSCode with the Flutter extension to run the app.

However, you can also run the app using the following command:

```bash
just run
```

### Running tests

```bash
just test
```

### Running coverage

```bash
just coverage
```
