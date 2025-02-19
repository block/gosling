use tokio::time::Duration;

#[flutter_rust_bridge::frb(sync)] // Synchronous mode for simplicity of the demo
pub fn greet(name: String) -> String {
    format!("Hello, {name}! This is from Rust!")
}

#[flutter_rust_bridge::frb(init)]
pub fn init_app() {
    // Default utilities - feel free to customize
    flutter_rust_bridge::setup_default_user_utils();
}

// New async function that simulates delay
#[flutter_rust_bridge::frb] // No sync attribute for async function
pub async fn greet_with_delay(name: String) -> String {
    // Simulate some processing time (2 seconds)
    tokio::time::sleep(Duration::from_secs(2)).await;
    format!("Hello, {name}! This delayed (async) greeting is from Rust!")
}
