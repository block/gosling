/// A class for validating environment variables.
///
/// Environment variables should be set in the `env.json` file.
///
/// This class is used to validate that the environment variables are correctly
/// set.
///
/// Important: DO NOT STORE SENSITIVE DATA IN THE `env.json` FILE.
class Env {
  static const String sampleEnvVar = String.fromEnvironment('SAMPLE_ENV_VAR');

  static void validate() {
    assert(sampleEnvVar.isNotEmpty, 'SAMPLE_ENV_VAR is not set');
  }
}
