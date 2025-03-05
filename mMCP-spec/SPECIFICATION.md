# mMCP SPEC (Mobile Model Context Protocol)

## Overview

mMCP enables apps to advertise available tools and instructions to AI models through Android's Intent system. This allows for dynamic discovery and invocation of functionality across apps.

## Core Components

### 1. Tool Definition

A tool is defined by three key components:
- **name**: Unique identifier for the tool
- **description**: Human-readable description of the tool's functionality
- **parameters**: JSON Schema defining the expected inputs

Example Tool:
```rust
let pdf_tool = Tool::new(
    "pdf_tool",
    indoc! {r#"
        Process PDF files to extract text and images.
        Supports operations:
        - extract_text: Extract all text content from the PDF
        - extract_images: Extract and save embedded images to PNG files
    "#},
    json!({
        "type": "object",
        "required": ["path", "operation"],
        "properties": {
            "path": {
                "type": "string",
                "description": "Path to the PDF file"
            },
            "operation": {
                "type": "string",
                "enum": ["extract_text", "extract_images"],
                "description": "Operation to perform on the PDF"
            }
        }
    }),
);
```

### 2. App Advertisement

Apps advertise their capabilities through an Intent with:
- Action: `com.example.mMCP.ACTION_TOOL_ADVERTISE`
- Meta-data containing:
  - **instructions**: A string providing general context about the app
  - **tools**: Array of available tools

Example manifest entry:
```xml
<activity android:name=".ToolAdvertiserActivity">
    <intent-filter>
        <action android:name="com.example.mMCP.ACTION_TOOL_ADVERTISE" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data
        android:name="mMCP_manifest"
        android:value='{
          "instructions": "This app provides PDF processing capabilities",
          "tools": [
            {
              "name": "pdf_tool",
              "description": "Process PDF files to extract text and images...",
              "parameters": {
                "type": "object",
                "required": ["path", "operation"],
                "properties": {
                  "path": {
                    "type": "string",
                    "description": "Path to the PDF file"
                  },
                  "operation": {
                    "type": "string",
                    "enum": ["extract_text", "extract_images"],
                    "description": "Operation to perform on the PDF"
                  }
                }
              }
            }
          ]
        }' />
</activity>
```

### 3. Tool Invocation

Tools are invoked using an Intent with:
- Action: `com.example.mMCP.ACTION_TOOL_CALL`
- Extras:
  - `tool_name`: Name of the tool to call
  - `parameters`: JSON string containing the tool parameters

Example invocation:
```kotlin
val callIntent = Intent("com.example.mMCP.ACTION_TOOL_CALL").apply {
    putExtra("tool_name", "pdf_tool")
    putExtra("parameters", """
        {
            "path": "/storage/docs/example.pdf",
            "operation": "extract_text"
        }
    """)
}
startActivityForResult(callIntent, TOOL_CALL_REQUEST_CODE)
```

### 4. Tool Discovery

Tools can be discovered by querying the PackageManager for activities that advertise the mMCP intent:

```kotlin
fun discoverTools(): List<ToolInfo> {
    val queryIntent = Intent("com.example.mMCP.ACTION_TOOL_ADVERTISE")
    return packageManager
        .queryIntentActivities(queryIntent, 0)
        .mapNotNull { info ->
            info.activityInfo.metaData
                ?.getString("mMCP_manifest")
                ?.let { Json.decodeFromString(it) }
        }
}
```

## Protocol Version

Current version: 1.0

The protocol version should be checked when discovering tools to ensure compatibility.