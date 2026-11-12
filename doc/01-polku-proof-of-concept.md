# **Polku: Interactive Self-Modifying Agent Loop**

## **Live-programming context ingestion and runtime agent loop system specification**

# **Executive Summary**

Polku is an interactive development agent loop bridging runtime application modification with user intent, inspired by the Clojure REPL live-programming paradigm. Designed for product owners, business users, and developers alike, Polku captures multimodal context (screen recording, microphone audio/voice, and mouse coordinate streams) during live application usage.

Instead of traditional static bug reporting or post-hoc text prompting, Polku enables users to point, annotate, speak their intent, and directly instruct an agent loop that applies self-modifying code changes in isolated runtime environments.

# **Project Vision & Core Principles**

* **Clojure REPL for Everyone**: Democratize the immediacy and feedback loop of interactive REPL-driven development for non-technical users and developers.  
* **Multimodal Context Ingestion**: Combine video (DOM/viewport capture), voice instructions, and synchronized mouse tracking data to eliminate ambiguities inherent in text-only issue tracking.  
* **Proactive Exploration over Bug Hunting**: Shift agent prompting from reactive error fixes to active feature exploration and expected behavior definition.  
* **Isolated, Branchable State**: Enable safe experimentation at runtime using branchable backend databases (such as Neon or Datomic) and isolated frontend runtimes.  
* **Zero-Friction Drop-in**: Packaged ideally as a single lightweight NPM library that developers can embed into web or mobile projects without complex build infrastructure.

# **Technical Architecture & Ingestion Design**

| Component | Layer | Primary Responsibilities & APIs |
| :---- | :---- | :---- |
| Client-Side Package | `polku-client` | Viewport capture (`getDisplayMedia` / DOM snapshots), microphone recording via `MediaRecorder`, timestamped mouse/event tracking, streaming chunk transport. |
| Ingestion & Relay | `polku-server` | Ingests media/audio/telemetry payloads locally, packages synchronized session artifacts into local workspace storage, exposes local MCP tools or CLI pipe integration. |
| Agent Execution | `polku-mcp` | Local MCP server tools (`get_session_recording`, `get_interaction_events`) feeding captured streams to an active Claude Code CLI session to edit local source code and trigger existing HMR build tools. |

## **Detailed Subsystem Architecture**

1. **Client-Side Capture (NPM Package)**  
   * **Screen / Viewport Capture**: Leveraging browser APIs such as `navigator.mediaDevices.getDisplayMedia()` or DOM snapshotting/serialization (e.g., rrweb style) to stream visual frame context.  
   * **Audio Stream & Transcripts**: Continuous or push-to-talk microphone audio via `MediaRecorder` to capture spoken user intent.  
   * **Mouse & Interaction Telemetry**: Event listeners tracking cursor coordinates, clicks, and element interaction paths, synchronized via high-precision timestamps relative to the media recording.  
   * **Data Transport**: Direct local streaming via HTTP POST or WebSockets to `polku-server` running inside the developer's local workstation environment.  
2. **Ingestion & Relay Backend**  
   * Accepts incoming media chunks, audio streams, and mouse coordinate payloads directly on localhost.  
   * Packages and syncs multimodal streams (video frames, audio transcripts, timestamped interaction coordinates) into local file system artifacts.  
   * Exposes local MCP (Model Context Protocol) server tools or a CLI pipe/file-drop mechanism for direct ingestion into an active Claude Code session.  
3. **Coding Agent Loop & Execution**  
   * Integrates directly into the developer's local workspace, local backend, and existing frontend build toolchain without requiring cloud-based or remote provisioning.  
   * Feeds captured multimodal artifacts into an active Claude Code CLI session via MCP tool calls or CLI prompt pipes.  
   * Claude Code interprets user intent, directly updates local code files in the repository workspace, and relies on the local app's existing HMR (Hot Module Replacement) and build tools for instant visual feedback.

# **Handover Specification for Claude Code Session**

## **Objective**

Bootstrap a local proof-of-concept repository and workspace integration for `polku` paired directly with an active Claude Code CLI session.

## **Primary Deliverables**

1. **`polku-client`**: A minimal client-side npm package that requests screen capture, records microphone audio, tracks mouse movements with timestamps, and streams the composite payload.  
2. **`polku-server`**: A local server receiving streaming HTTP POST / WebSocket data from `polku-client`, writing synchronized session artifacts locally in the workspace.  
3. **`polku-mcp`**: A local MCP tool server (or CLI pipe mechanism) exposing `get_session_recording`, `get_interaction_events`, and `get_context_summary` directly to Claude Code CLI.  
4. **Local Data Flow & Reload Loop**: Document and verify the step-by-step pipeline: `polku-client` captures telemetry \-\> `polku-server` packages artifacts locally \-\> Claude Code ingests context via MCP or CLI prompt injection \-\> Claude Code modifies source files directly \-\> Local build process/HMR instantly updates the running app.

## **Local Watcher / Polling Mechanism for Claude Code Integration**

5. **Architectural Role**: Bridges the gap between the asynchronous browser capture stream and the active Claude Code CLI session without waiting for manual human prompting.  
6. **Ingest Directory Structure**: Polku backend writes completed or chunked session artifacts to a local directory (e.g., `.polku/sessions/<session_id>/` containing `manifest.json`, `events.jsonl`, `audio.webm`/transcript, and extracted frames).  
7. **Polling / Watcher Daemon**: A lightweight file-watcher script or daemon (e.g., using `chokidar` or a 5-10s polling loop) monitors `.polku/sessions/`.  
8. **Claude Code Context Injection**: Once a new session or actionable event is detected, the script automatically pipes or formats the session payload into Claude Code's working environment (e.g., writing an active context prompt file like `.polku/CURRENT_TASK.md` or triggering Claude Code via its CLI / stdin / automated skill instruction).  
9. **Fast PoC Loop**: Enables near-realtime handoff from end-user voice/pointer interaction directly into Claude Code code generation, followed by local HMR reload.

## **Implementation Guidelines**

* Eliminate external cloud environment dependencies; focus entirely on local workspace integration.  
* Ensure tight integration with existing developer build tooling and live-reloading setups (e.g. Vite, Next.js HMR).  
* Implement robust synchronization of timestamps between mouse telemetry, visual frames, audio/transcripts, and local workspace file modifications.

# **Session Transcripts**

## **Session 1: Initial Concept & Architecture Discussion**

* **Topic**: Autonomous, self-modifying agent loop combining Clojure REPL live-programming with business user intent.  
* **Key Points Discussed**:  
  * Bridging runtime code modification with non-technical user feedback.  
  * Capturing multimodal streams: voice descriptions, screen recordings, and sensor data.  
  * Using branchable databases (Neon, Datomic) to isolate environments for safe stateful experimentation.  
  * Synthesizing multimodal input into a reliable payload for coding agents.

## **Session 2: Implementation Details, Browser Capabilities & Naming**

* **Screen & Audio APIs**: Evaluated `navigator.mediaDevices.getDisplayMedia()`, DOM serialization vs. video streams, and overlay handling.  
* **Mouse Tracking**: Confirmed requirement to track mouse coordinates and events independently and synchronize them with audio/video.  
* **Transport**: Evaluated long-lived HTTP POST vs. WebSockets for unidirectional telemetry ingest.  
* **Model Ingestion & Multimodality**: Discussed native audio token models vs. transcription pipelines, computer use APIs, and binary payload handling via MCP tools.  
* **Project Naming**: Selected **Polku** (Finnish for *"path"*), complementing existing Finnish-named Clojure ecosystem libraries like Reitit while denoting a user's navigation path and agent workflow.

