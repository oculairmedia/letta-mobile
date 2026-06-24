# meridian-app-server

Standalone host CLI for the official Letta App Server path. It is intentionally
small: `app-server-serve` launches the upstream `letta app-server` process, and
`app-server-smoke` sends one typed turn through the shared App Server client in
`:sharedLogic`.

## Build

```powershell
cd android-compose
$env:JAVA_HOME="C:\Users\Emmanuel\.cache\jdk26-temurin\jdk-26.0.1+8"
.\gradlew.bat :appserver-cli:distZip
```

The zip is written under `appserver-cli/build/distributions/`. Unzip it and run
`bin/meridian-app-server` or `bin/meridian-app-server.bat` with Java 17 or newer.

## Commands

```powershell
.\bin\meridian-app-server.bat app-server-serve --listen ws://127.0.0.1:4500
.\bin\meridian-app-server.bat app-server-serve --listen ws://0.0.0.0:4500 --ws-auth capability-token --ws-token-file .\token.txt --ws-token-sha256 <sha256>
.\bin\meridian-app-server.bat app-server-serve --letta-command pnpm --letta-arg dlx --letta-arg @letta-ai/letta-code@0.27.15 --dry-run
```

Smoke a running App Server:

```powershell
$env:APP_SERVER_TEST_URL="ws://127.0.0.1:4500"
$env:APP_SERVER_TEST_AGENT_ID="agt_x"
$env:APP_SERVER_TEST_CONVERSATION_ID="conv_x"
.\bin\meridian-app-server.bat app-server-smoke --message "hello"
```

Set `APP_SERVER_TEST_TOKEN` or pass `--token` when connecting to a non-loopback
server that requires bearer auth.
